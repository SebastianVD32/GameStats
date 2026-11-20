package matchstate

import (
	"testing"

	"github.com/SebastianVD32/GameStats/go-ingestion/internal/domain"
)

func TestRacingAbortLifecycle(t *testing.T) {
	for _, reason := range []string{"USER_QUIT", "RESTARTED", "PAGE_CLOSED"} {
		t.Run(reason, func(t *testing.T) {
			m := mustStart(t, testEvent(domain.GameRacing, "abort", 1, nil, domain.EventMatchStarted,
				domain.RacingStartData{DriverIDs: []string{"a", "b"}, PlannedLaps: 3}))
			a := "a"
			if _, err := m.Apply(testEvent(domain.GameRacing, "abort", 2, &a, eventLapCompleted,
				domain.LapData{LapNumber: 1, LapTimeMs: 10000, Position: 1})); err != nil {
				t.Fatal(err)
			}
			end := testEvent(domain.GameRacing, "abort", 3, nil, domain.EventMatchAborted, domain.RacingAbortData{Reason: reason})
			if closed, err := m.Apply(end); err != nil || !closed || !m.Finished() {
				t.Fatalf("abort = %v, %v", closed, err)
			}
			if _, err := m.Apply(testEvent(domain.GameRacing, "abort", 4, &a, eventLapCompleted,
				domain.LapData{LapNumber: 2, LapTimeMs: 10000, Position: 1})); err == nil {
				t.Fatal("action after abort accepted")
			}
		})
	}
}

func TestRacingAbortValidation(t *testing.T) {
	for _, tc := range []struct {
		name    string
		payload any
		player  *string
	}{
		{"unknown reason", map[string]any{"reason": "OTHER"}, nil},
		{"extra classification", map[string]any{"reason": "USER_QUIT", "classification": []any{}}, nil},
		{"missing reason", map[string]any{}, nil},
		{"player in envelope", domain.RacingAbortData{Reason: "USER_QUIT"}, func() *string { s := "a"; return &s }()},
	} {
		t.Run(tc.name, func(t *testing.T) {
			m := mustStart(t, testEvent(domain.GameRacing, "abort", 1, nil, domain.EventMatchStarted,
				domain.RacingStartData{DriverIDs: []string{"a", "b"}, PlannedLaps: 1}))
			if _, err := m.Apply(testEvent(domain.GameRacing, "abort", 2, tc.player, domain.EventMatchAborted, tc.payload)); err == nil {
				t.Fatal("invalid abort accepted")
			}
			if m.Finished() {
				t.Fatal("rejected abort changed state")
			}
		})
	}
}

func TestRacingAbortAllowsNoLapsOrOpenPitButNotACompletedRace(t *testing.T) {
	start := testEvent(domain.GameRacing, "abort", 1, nil, domain.EventMatchStarted,
		domain.RacingStartData{DriverIDs: []string{"a", "b"}, PlannedLaps: 1})
	empty := mustStart(t, start)
	if _, err := empty.Apply(testEvent(domain.GameRacing, "abort", 2, nil, domain.EventMatchAborted,
		domain.RacingAbortData{Reason: "RESTARTED"})); err != nil {
		t.Fatal(err)
	}
	open := mustStart(t, start)
	a, b := "a", "b"
	_, err := open.Apply(testEvent(domain.GameRacing, "abort", 2, &a, eventPitEntered,
		domain.PitEntryData{StopID: "pit", LapNumber: 1, Reason: "tires"}))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := open.Apply(testEvent(domain.GameRacing, "abort", 3, nil, domain.EventMatchAborted,
		domain.RacingAbortData{Reason: "PAGE_CLOSED"})); err != nil {
		t.Fatal(err)
	}
	complete := mustStart(t, start)
	for i, id := range []*string{&a, &b} {
		if _, err := complete.Apply(testEvent(domain.GameRacing, "abort", int64(i+2), id, eventLapCompleted,
			domain.LapData{LapNumber: 1, LapTimeMs: int64(10000 + i), Position: i + 1})); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := complete.Apply(testEvent(domain.GameRacing, "abort", 4, nil, domain.EventMatchAborted,
		domain.RacingAbortData{Reason: "USER_QUIT"})); err == nil {
		t.Fatal("completed race aborted")
	}
}

func TestAbortIsNotAcceptedForOtherGames(t *testing.T) {
	m := mustStart(t, testEvent(domain.GamePacMan, "pac", 1, nil, domain.EventMatchStarted,
		domain.PacManStartData{PlayerID: "p", InitialLives: 3, InitialLevel: 1}))
	if _, err := m.Apply(testEvent(domain.GamePacMan, "pac", 2, nil, domain.EventMatchAborted,
		domain.RacingAbortData{Reason: "USER_QUIT"})); err == nil {
		t.Fatal("Pac-Man accepted racing abort")
	}
}
