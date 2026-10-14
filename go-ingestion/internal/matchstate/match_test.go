package matchstate

import (
	"encoding/json"
	"fmt"
	"strings"
	"testing"
	"time"

	"github.com/SebastianVD32/GameStats/go-ingestion/internal/domain"
)

func TestChessMatchLifecycle(t *testing.T) {
	t.Parallel()
	start := testEvent(domain.GameChess, "chess-1", 1, nil, domain.EventMatchStarted, domain.ChessStartData{
		Participants:   []domain.ChessParticipant{{PlayerID: "white", Color: "WHITE"}, {PlayerID: "black", Color: "BLACK"}},
		InitialClockMs: 300_000,
		IncrementMs:    2_000,
	})
	match, err := Start(start)
	if err != nil {
		t.Fatalf("Start() error = %v", err)
	}

	white := "white"
	move := testEvent(domain.GameChess, "chess-1", 2, &white, eventMovePlayed, domain.ChessMoveData{
		MoveNumber: 1, Piece: "PAWN", From: "e2", To: "e4",
		DecisionTimeMs: 5_000, ClockBeforeMs: 300_000, ClockAfterMs: 297_000,
	})
	move.EventID = "move-1"
	if finished, err := match.Apply(move); err != nil || finished {
		t.Fatalf("Apply(move) = (%v, %v)", finished, err)
	}

	black := "black"
	capture := testEvent(domain.GameChess, "chess-1", 3, &white, eventPieceCaptured, domain.ChessCaptureData{
		MoveEventID: "move-1", CapturedPiece: "PAWN", CapturedPlayerID: black,
	})
	if _, err := match.Apply(capture); err != nil {
		t.Fatalf("Apply(capture) error = %v", err)
	}

	finish := testEvent(domain.GameChess, "chess-1", 4, nil, domain.EventMatchFinished, domain.ChessFinishData{
		WinnerPlayerID: &white, Reason: "RESIGNATION",
	})
	if finished, err := match.Apply(finish); err != nil || !finished {
		t.Fatalf("Apply(finish) = (%v, %v)", finished, err)
	}
	if got := len(match.Batch().Events); got != 4 {
		t.Fatalf("Batch() contiene %d eventos, se esperaban 4", got)
	}
}

func TestChessRejectsWrongTurnWithoutMutatingOriginalClone(t *testing.T) {
	t.Parallel()
	match := mustStart(t, testEvent(domain.GameChess, "chess-2", 1, nil, domain.EventMatchStarted, domain.ChessStartData{
		Participants:   []domain.ChessParticipant{{PlayerID: "white", Color: "WHITE"}, {PlayerID: "black", Color: "BLACK"}},
		InitialClockMs: 60_000,
		IncrementMs:    0,
	}))
	black := "black"
	bad := testEvent(domain.GameChess, "chess-2", 2, &black, eventMovePlayed, domain.ChessMoveData{
		MoveNumber: 1, Piece: "PAWN", From: "e7", To: "e5",
		DecisionTimeMs: 1_000, ClockBeforeMs: 60_000, ClockAfterMs: 59_000,
	})
	if _, err := match.Clone().Apply(bad); err == nil || !strings.Contains(err.Error(), "WHITE") {
		t.Fatalf("Apply() error = %v", err)
	}
	if got := len(match.Batch().Events); got != 1 {
		t.Fatalf("el estado original fue modificado; contiene %d eventos", got)
	}
}

func TestBasketballFifthFoulAndScoreValidation(t *testing.T) {
	t.Parallel()
	match := mustStart(t, testEvent(domain.GameBasketball, "basket-1", 1, nil, domain.EventMatchStarted, domain.BasketballStartData{
		Teams: []domain.BasketballTeam{
			{TeamID: "home", PlayerIDs: []string{"h1", "h2"}},
			{TeamID: "away", PlayerIDs: []string{"a1", "a2"}},
		},
		Periods: 4,
	}))
	h1 := "h1"
	shot := testEvent(domain.GameBasketball, "basket-1", 2, &h1, eventShotAttempted, domain.ShotData{
		TeamID: "home", Period: 1, PeriodTimeRemainingMs: 500_000, ShotValue: 3, Made: true,
	})
	shot.EventID = "shot-1"
	if _, err := match.Apply(shot); err != nil {
		t.Fatalf("Apply(shot) error = %v", err)
	}

	for sequence := int64(3); sequence <= 7; sequence++ {
		foul := testEvent(domain.GameBasketball, "basket-1", sequence, &h1, eventFoulCommitted, domain.FoulData{
			TeamID: "home", Period: 1, AffectedPlayerID: "a1", Reason: "personal",
		})
		if _, err := match.Apply(foul); err != nil {
			t.Fatalf("Apply(foul %d) error = %v", sequence-2, err)
		}
	}

	badAction := testEvent(domain.GameBasketball, "basket-1", 8, &h1, eventTurnoverCommitted, domain.TurnoverData{
		TeamID: "home", Period: 1, Reason: "travel",
	})
	if _, err := match.Clone().Apply(badAction); err == nil || !strings.Contains(err.Error(), "cinco faltas") {
		t.Fatalf("Apply(after fifth foul) error = %v", err)
	}

	finish := testEvent(domain.GameBasketball, "basket-1", 8, nil, domain.EventMatchFinished, domain.BasketballFinishData{
		Scores: []domain.TeamScore{{TeamID: "home", Points: 3}, {TeamID: "away", Points: 0}},
	})
	if finished, err := match.Apply(finish); err != nil || !finished {
		t.Fatalf("Apply(finish) = (%v, %v)", finished, err)
	}
}

func TestBasketballRejectsInvalidAssist(t *testing.T) {
	t.Parallel()
	match := mustStart(t, testEvent(domain.GameBasketball, "basket-2", 1, nil, domain.EventMatchStarted, domain.BasketballStartData{
		Teams: []domain.BasketballTeam{
			{TeamID: "home", PlayerIDs: []string{"h1", "h2"}},
			{TeamID: "away", PlayerIDs: []string{"a1"}},
		}, Periods: 4,
	}))
	h1 := "h1"
	shot := testEvent(domain.GameBasketball, "basket-2", 2, &h1, eventShotAttempted, domain.ShotData{
		TeamID: "home", Period: 1, PeriodTimeRemainingMs: 10, ShotValue: 1, Made: true,
	})
	shot.EventID = "free-throw"
	if _, err := match.Apply(shot); err != nil {
		t.Fatal(err)
	}
	h2 := "h2"
	assist := testEvent(domain.GameBasketball, "basket-2", 3, &h2, eventAssistRecorded, domain.AssistData{
		TeamID: "home", Period: 1, ScorerID: "h1", ShotEventID: "free-throw",
	})
	if _, err := match.Apply(assist); err == nil || !strings.Contains(err.Error(), "canasta de campo") {
		t.Fatalf("Apply(assist) error = %v", err)
	}
}

func TestRacingLifecycleAndPitRelationship(t *testing.T) {
	t.Parallel()
	match := mustStart(t, testEvent(domain.GameRacing, "race-1", 1, nil, domain.EventMatchStarted, domain.RacingStartData{
		DriverIDs: []string{"d1", "d2"}, PlannedLaps: 1,
	}))
	d1, d2 := "d1", "d2"
	events := []domain.Event{
		testEvent(domain.GameRacing, "race-1", 2, &d1, eventPitEntered, domain.PitEntryData{StopID: "stop-1", LapNumber: 1, Reason: "tires"}),
		testEvent(domain.GameRacing, "race-1", 3, &d1, eventPitExited, domain.PitExitData{StopID: "stop-1", LapNumber: 1, TireCompound: "SOFT"}),
		testEvent(domain.GameRacing, "race-1", 4, &d1, eventLapCompleted, domain.LapData{LapNumber: 1, LapTimeMs: 90_000, Position: 1}),
		testEvent(domain.GameRacing, "race-1", 5, &d2, eventLapCompleted, domain.LapData{LapNumber: 1, LapTimeMs: 91_000, Position: 2}),
	}
	for _, event := range events {
		if _, err := match.Apply(event); err != nil {
			t.Fatalf("Apply(%s) error = %v", event.Type, err)
		}
	}
	finish := testEvent(domain.GameRacing, "race-1", 6, nil, domain.EventMatchFinished, domain.RacingFinishData{
		Classification: []domain.ClassificationEntry{{PlayerID: d1, Position: 1}, {PlayerID: d2, Position: 2}},
	})
	if finished, err := match.Apply(finish); err != nil || !finished {
		t.Fatalf("Apply(finish) = (%v, %v)", finished, err)
	}
}

func TestRacingRejectsPitExitWithoutEntry(t *testing.T) {
	t.Parallel()
	match := mustStart(t, testEvent(domain.GameRacing, "race-2", 1, nil, domain.EventMatchStarted, domain.RacingStartData{
		DriverIDs: []string{"d1", "d2"}, PlannedLaps: 2,
	}))
	d1 := "d1"
	exit := testEvent(domain.GameRacing, "race-2", 2, &d1, eventPitExited, domain.PitExitData{
		StopID: "missing", LapNumber: 1, TireCompound: "MEDIUM",
	})
	if _, err := match.Apply(exit); err == nil || !strings.Contains(err.Error(), "entrada a boxes") {
		t.Fatalf("Apply(exit) error = %v", err)
	}
}

func TestPacManLifecycleAndScoring(t *testing.T) {
	t.Parallel()
	match := mustStart(t, testEvent(domain.GamePacMan, "pacman-1", 1, nil, domain.EventMatchStarted, domain.PacManStartData{
		PlayerID: "pac-alice", InitialLevel: 1, InitialLives: 3,
	}))
	player := "pac-alice"
	powerUp := testEvent(domain.GamePacMan, "pacman-1", 3, &player, eventPowerUpCollected, domain.PowerUpData{
		Level: 1, PelletID: "power-1", DurationMs: 10_000,
	})
	powerUp.EventID = "power-event-1"
	events := []domain.Event{
		testEvent(domain.GamePacMan, "pacman-1", 2, &player, eventPelletEaten, domain.PelletData{Level: 1, PelletID: "pellet-1"}),
		powerUp,
		testEvent(domain.GamePacMan, "pacman-1", 4, &player, eventGhostEaten, domain.GhostData{Level: 1, GhostID: "BLINKY", PowerUpEventID: "power-event-1"}),
		testEvent(domain.GamePacMan, "pacman-1", 5, &player, eventGhostEaten, domain.GhostData{Level: 1, GhostID: "PINKY", PowerUpEventID: "power-event-1"}),
		testEvent(domain.GamePacMan, "pacman-1", 6, &player, eventGhostEaten, domain.GhostData{Level: 1, GhostID: "INKY", PowerUpEventID: "power-event-1"}),
		testEvent(domain.GamePacMan, "pacman-1", 7, &player, eventLevelCompleted, domain.LevelCompletedData{Level: 1}),
		testEvent(domain.GamePacMan, "pacman-1", 8, &player, eventLifeLost, domain.LifeLostData{Level: 2, Cause: "GHOST_COLLISION", LivesRemaining: 2}),
	}
	for _, event := range events {
		if _, err := match.Apply(event); err != nil {
			t.Fatalf("Apply(%s) error = %v", event.Type, err)
		}
	}

	finish := testEvent(domain.GamePacMan, "pacman-1", 9, nil, domain.EventMatchFinished, domain.PacManFinishData{
		Score: 1460, Reason: "SESSION_ENDED",
	})
	if finished, err := match.Apply(finish); err != nil || !finished {
		t.Fatalf("Apply(finish) = (%v, %v)", finished, err)
	}
	if got := len(match.Batch().Events); got != 9 {
		t.Fatalf("Batch() contiene %d eventos, se esperaban 9", got)
	}
}

func TestPacManRejectsExpiredPowerAndDuplicatePellet(t *testing.T) {
	t.Parallel()
	player := "pac-bob"

	t.Run("expired power", func(t *testing.T) {
		match := mustStart(t, testEvent(domain.GamePacMan, "pacman-expired", 1, nil, domain.EventMatchStarted, domain.PacManStartData{
			PlayerID: player, InitialLevel: 1, InitialLives: 3,
		}))
		powerUp := testEvent(domain.GamePacMan, "pacman-expired", 2, &player, eventPowerUpCollected, domain.PowerUpData{
			Level: 1, PelletID: "power-1", DurationMs: 1_000,
		})
		powerUp.EventID = "short-power"
		if _, err := match.Apply(powerUp); err != nil {
			t.Fatal(err)
		}
		ghost := testEvent(domain.GamePacMan, "pacman-expired", 3, &player, eventGhostEaten, domain.GhostData{
			Level: 1, GhostID: "BLINKY", PowerUpEventID: "short-power",
		})
		if _, err := match.Clone().Apply(ghost); err == nil || !strings.Contains(err.Error(), "intervalo activo") {
			t.Fatalf("Apply(expired ghost) error = %v", err)
		}
	})

	t.Run("duplicate pellet", func(t *testing.T) {
		match := mustStart(t, testEvent(domain.GamePacMan, "pacman-duplicate", 1, nil, domain.EventMatchStarted, domain.PacManStartData{
			PlayerID: player, InitialLevel: 1, InitialLives: 3,
		}))
		pellet := testEvent(domain.GamePacMan, "pacman-duplicate", 2, &player, eventPelletEaten, domain.PelletData{
			Level: 1, PelletID: "shared-pellet",
		})
		if _, err := match.Apply(pellet); err != nil {
			t.Fatal(err)
		}
		duplicate := testEvent(domain.GamePacMan, "pacman-duplicate", 3, &player, eventPowerUpCollected, domain.PowerUpData{
			Level: 1, PelletID: "shared-pellet", DurationMs: 5_000,
		})
		if _, err := match.Clone().Apply(duplicate); err == nil || !strings.Contains(err.Error(), "ya fue consumido") {
			t.Fatalf("Apply(duplicate pellet) error = %v", err)
		}
	})
}

func TestPacManNewPowerResetsGhostChain(t *testing.T) {
	t.Parallel()
	player := "pac-dan"
	match := mustStart(t, testEvent(domain.GamePacMan, "pacman-reset", 1, nil, domain.EventMatchStarted, domain.PacManStartData{
		PlayerID: player, InitialLevel: 1, InitialLives: 3,
	}))
	firstPower := testEvent(domain.GamePacMan, "pacman-reset", 2, &player, eventPowerUpCollected, domain.PowerUpData{
		Level: 1, PelletID: "power-1", DurationMs: 10_000,
	})
	firstPower.EventID = "first-power"
	secondPower := testEvent(domain.GamePacMan, "pacman-reset", 4, &player, eventPowerUpCollected, domain.PowerUpData{
		Level: 1, PelletID: "power-2", DurationMs: 10_000,
	})
	secondPower.EventID = "second-power"
	events := []domain.Event{
		firstPower,
		testEvent(domain.GamePacMan, "pacman-reset", 3, &player, eventGhostEaten, domain.GhostData{Level: 1, GhostID: "BLINKY", PowerUpEventID: "first-power"}),
		secondPower,
		testEvent(domain.GamePacMan, "pacman-reset", 5, &player, eventGhostEaten, domain.GhostData{Level: 1, GhostID: "BLINKY", PowerUpEventID: "second-power"}),
	}
	for _, event := range events {
		if _, err := match.Apply(event); err != nil {
			t.Fatalf("Apply(%s) error = %v", event.Type, err)
		}
	}
	finish := testEvent(domain.GamePacMan, "pacman-reset", 6, nil, domain.EventMatchFinished, domain.PacManFinishData{
		Score: 500, Reason: "SESSION_ENDED",
	})
	if finished, err := match.Apply(finish); err != nil || !finished {
		t.Fatalf("Apply(finish) = (%v, %v)", finished, err)
	}
}

func TestPacManAllowsOnlyFinishAfterLastLife(t *testing.T) {
	t.Parallel()
	player := "pac-carol"
	match := mustStart(t, testEvent(domain.GamePacMan, "pacman-lives", 1, nil, domain.EventMatchStarted, domain.PacManStartData{
		PlayerID: player, InitialLevel: 1, InitialLives: 1,
	}))
	lostLife := testEvent(domain.GamePacMan, "pacman-lives", 2, &player, eventLifeLost, domain.LifeLostData{
		Level: 1, Cause: "GHOST_COLLISION", LivesRemaining: 0,
	})
	if _, err := match.Apply(lostLife); err != nil {
		t.Fatal(err)
	}
	pellet := testEvent(domain.GamePacMan, "pacman-lives", 3, &player, eventPelletEaten, domain.PelletData{
		Level: 1, PelletID: "too-late",
	})
	if _, err := match.Clone().Apply(pellet); err == nil || !strings.Contains(err.Error(), "solamente se acepta MATCH_FINISHED") {
		t.Fatalf("Apply(after last life) error = %v", err)
	}
	finish := testEvent(domain.GamePacMan, "pacman-lives", 3, nil, domain.EventMatchFinished, domain.PacManFinishData{
		Score: 0, Reason: "GAME_OVER",
	})
	if finished, err := match.Apply(finish); err != nil || !finished {
		t.Fatalf("Apply(finish) = (%v, %v)", finished, err)
	}
}

func mustStart(t *testing.T, event domain.Event) *Match {
	t.Helper()
	match, err := Start(event)
	if err != nil {
		t.Fatalf("Start() error = %v", err)
	}
	return match
}

func testEvent(gameID domain.GameID, matchID string, sequence int64, playerID *string, eventType string, data any) domain.Event {
	raw, err := json.Marshal(data)
	if err != nil {
		panic(err)
	}
	return domain.Event{
		EventID:     fmt.Sprintf("%s-%d", matchID, sequence),
		Timestamp:   time.Date(2026, time.September, 1, 12, 0, int(sequence), 0, time.UTC),
		GameID:      gameID,
		GameVersion: "1.0",
		MatchID:     matchID,
		Sequence:    sequence,
		PlayerID:    playerID,
		Type:        eventType,
		Data:        raw,
	}
}
