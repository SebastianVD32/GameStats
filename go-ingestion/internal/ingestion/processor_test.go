package ingestion

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"sync"
	"testing"
	"time"

	"github.com/SebastianVD32/GameStats/go-ingestion/internal/domain"
)

type recordingSink struct {
	mu       sync.Mutex
	batches  []domain.MatchBatch
	failNext bool
}

func (s *recordingSink) Deliver(_ context.Context, batch domain.MatchBatch) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.failNext {
		s.failNext = false
		return errors.New("fallo simulado")
	}
	s.batches = append(s.batches, batch)
	return nil
}

func TestProcessorDeduplicatesGlobally(t *testing.T) {
	t.Parallel()
	processor, err := NewProcessor(2, &recordingSink{})
	if err != nil {
		t.Fatal(err)
	}
	defer processor.Close()

	event1 := chessStart("m1", "same-event")
	if _, err := processor.Process(context.Background(), event1); err != nil {
		t.Fatalf("Process(first) error = %v", err)
	}
	event2 := chessStart("m2", "same-event")
	if _, err := processor.Process(context.Background(), event2); CodeOf(err) != CodeDuplicate {
		t.Fatalf("Process(duplicate) error = %v, code = %s", err, CodeOf(err))
	}
}

func TestProcessorRoutesDifferentMatchesConcurrently(t *testing.T) {
	t.Parallel()
	processor, err := NewProcessor(4, &recordingSink{})
	if err != nil {
		t.Fatal(err)
	}
	defer processor.Close()

	var waitGroup sync.WaitGroup
	errorsChannel := make(chan error, 20)
	for index := 0; index < 20; index++ {
		waitGroup.Add(1)
		go func(index int) {
			defer waitGroup.Done()
			matchID := fmt.Sprintf("match-%d", index)
			_, err := processor.Process(context.Background(), chessStart(matchID, "start-"+matchID))
			errorsChannel <- err
		}(index)
	}
	waitGroup.Wait()
	close(errorsChannel)
	for err := range errorsChannel {
		if err != nil {
			t.Errorf("Process() error = %v", err)
		}
	}
}

func TestProcessorRollsBackFinishWhenDeliveryFails(t *testing.T) {
	t.Parallel()
	sink := &recordingSink{failNext: true}
	processor, err := NewProcessor(1, sink)
	if err != nil {
		t.Fatal(err)
	}
	defer processor.Close()

	start := chessStart("m1", "start-1")
	if _, err := processor.Process(context.Background(), start); err != nil {
		t.Fatal(err)
	}
	winner := "white"
	finish := event(domain.GameChess, "m1", "finish-1", 2, nil, domain.EventMatchFinished, domain.ChessFinishData{
		WinnerPlayerID: &winner, Reason: "CHECKMATE",
	})
	if _, err := processor.Process(context.Background(), finish); CodeOf(err) != CodeDeliveryFailed {
		t.Fatalf("Process(failed delivery) error = %v", err)
	}
	batch, err := processor.Match(context.Background(), "m1")
	if err != nil {
		t.Fatal(err)
	}
	if len(batch.Events) != 1 {
		t.Fatalf("se confirmo un evento sin entregar; total = %d", len(batch.Events))
	}
	outcome, err := processor.Process(context.Background(), finish)
	if err != nil || !outcome.Completed || !outcome.Delivered {
		t.Fatalf("Process(retry) = (%#v, %v)", outcome, err)
	}
}

func chessStart(matchID, eventID string) domain.Event {
	return event(domain.GameChess, matchID, eventID, 1, nil, domain.EventMatchStarted, domain.ChessStartData{
		Participants:   []domain.ChessParticipant{{PlayerID: "white", Color: "WHITE"}, {PlayerID: "black", Color: "BLACK"}},
		InitialClockMs: 60_000,
		IncrementMs:    0,
	})
}

func event(gameID domain.GameID, matchID, eventID string, sequence int64, playerID *string, eventType string, data any) domain.Event {
	raw, err := json.Marshal(data)
	if err != nil {
		panic(err)
	}
	return domain.Event{
		EventID: eventID, Timestamp: time.Date(2026, 9, 1, 12, 0, int(sequence), 0, time.UTC),
		GameID: gameID, GameVersion: "1.0", MatchID: matchID, Sequence: sequence,
		PlayerID: playerID, Type: eventType, Data: raw,
	}
}
