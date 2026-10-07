package domain

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"strings"
	"time"
)

type GameID string

const (
	GameChess      GameID = "CHESS"
	GameBasketball GameID = "BASKETBALL"
	GameRacing     GameID = "RACING"

	EventMatchStarted  = "MATCH_STARTED"
	EventMatchFinished = "MATCH_FINISHED"
)

type Event struct {
	EventID     string          `json:"eventId"`
	Timestamp   time.Time       `json:"timestamp"`
	GameID      GameID          `json:"gameId"`
	GameVersion string          `json:"gameVersion"`
	MatchID     string          `json:"matchId"`
	Sequence    int64           `json:"sequence"`
	PlayerID    *string         `json:"playerId"`
	Type        string          `json:"type"`
	Data        json.RawMessage `json:"data"`
}

type MatchBatch struct {
	GameID      GameID  `json:"gameId"`
	GameVersion string  `json:"gameVersion"`
	MatchID     string  `json:"matchId"`
	Events      []Event `json:"events"`
}

type ChessParticipant struct {
	PlayerID string `json:"playerId"`
	Color    string `json:"color"`
}

type ChessStartData struct {
	Participants   []ChessParticipant `json:"participants"`
	InitialClockMs int64              `json:"initialClockMs"`
	IncrementMs    int64              `json:"incrementMs"`
}

type ChessMoveData struct {
	MoveNumber     int64  `json:"moveNumber"`
	Piece          string `json:"piece"`
	From           string `json:"from"`
	To             string `json:"to"`
	DecisionTimeMs int64  `json:"decisionTimeMs"`
	ClockBeforeMs  int64  `json:"clockBeforeMs"`
	ClockAfterMs   int64  `json:"clockAfterMs"`
}

type ChessCaptureData struct {
	MoveEventID      string `json:"moveEventId"`
	CapturedPiece    string `json:"capturedPiece"`
	CapturedPlayerID string `json:"capturedPlayerId"`
}

type ChessFinishData struct {
	WinnerPlayerID *string `json:"winnerPlayerId"`
	Reason         string  `json:"reason"`
}

type BasketballTeam struct {
	TeamID    string   `json:"teamId"`
	PlayerIDs []string `json:"playerIds"`
}

type BasketballStartData struct {
	Teams   []BasketballTeam `json:"teams"`
	Periods int              `json:"periods"`
}

type ShotData struct {
	TeamID                string `json:"teamId"`
	Period                int    `json:"period"`
	PeriodTimeRemainingMs int64  `json:"periodTimeRemainingMs"`
	ShotValue             int    `json:"shotValue"`
	Made                  bool   `json:"made"`
}

type AssistData struct {
	TeamID      string `json:"teamId"`
	Period      int    `json:"period"`
	ScorerID    string `json:"scorerId"`
	ShotEventID string `json:"shotEventId"`
}

type ReboundData struct {
	TeamID      string `json:"teamId"`
	Period      int    `json:"period"`
	ReboundType string `json:"reboundType"`
	ShotEventID string `json:"shotEventId"`
}

type TurnoverData struct {
	TeamID string `json:"teamId"`
	Period int    `json:"period"`
	Reason string `json:"reason"`
}

type FoulData struct {
	TeamID           string `json:"teamId"`
	Period           int    `json:"period"`
	AffectedPlayerID string `json:"affectedPlayerId"`
	Reason           string `json:"reason"`
}

type TeamScore struct {
	TeamID string `json:"teamId"`
	Points int    `json:"points"`
}

type BasketballFinishData struct {
	Scores []TeamScore `json:"scores"`
}

type RacingStartData struct {
	DriverIDs   []string `json:"driverIds"`
	PlannedLaps int      `json:"plannedLaps"`
}

type LapData struct {
	LapNumber int   `json:"lapNumber"`
	LapTimeMs int64 `json:"lapTimeMs"`
	Position  int   `json:"position"`
}

type PitEntryData struct {
	StopID    string `json:"stopId"`
	LapNumber int    `json:"lapNumber"`
	Reason    string `json:"reason"`
}

type PitExitData struct {
	StopID       string `json:"stopId"`
	LapNumber    int    `json:"lapNumber"`
	TireCompound string `json:"tireCompound"`
}

type PenaltyData struct {
	PenaltyType string `json:"penaltyType"`
	Value       int64  `json:"value"`
	Reason      string `json:"reason"`
	LapNumber   int    `json:"lapNumber"`
}

type ClassificationEntry struct {
	PlayerID string `json:"playerId"`
	Position int    `json:"position"`
}

type RacingFinishData struct {
	Classification []ClassificationEntry `json:"classification"`
}

type eventWire struct {
	EventID     string          `json:"eventId"`
	Timestamp   string          `json:"timestamp"`
	GameID      GameID          `json:"gameId"`
	GameVersion string          `json:"gameVersion"`
	MatchID     string          `json:"matchId"`
	Sequence    int64           `json:"sequence"`
	PlayerID    *string         `json:"playerId"`
	Type        string          `json:"type"`
	Data        json.RawMessage `json:"data"`
}

var topLevelFields = []string{
	"eventId", "timestamp", "gameId", "gameVersion", "matchId",
	"sequence", "playerId", "type", "data",
}

func DecodeEvent(data []byte) (Event, error) {
	var fields map[string]json.RawMessage
	if err := decodeStrict(data, &fields); err != nil {
		return Event{}, fmt.Errorf("JSON de evento invalido: %w", err)
	}
	for _, field := range topLevelFields {
		if _, ok := fields[field]; !ok {
			return Event{}, fmt.Errorf("falta el campo obligatorio %q", field)
		}
	}

	var wire eventWire
	if err := decodeStrict(data, &wire); err != nil {
		return Event{}, fmt.Errorf("estructura de evento invalida: %w", err)
	}

	timestamp, err := time.Parse(time.RFC3339Nano, wire.Timestamp)
	if err != nil {
		return Event{}, fmt.Errorf("timestamp debe usar ISO 8601/RFC 3339: %w", err)
	}
	_, offset := timestamp.Zone()
	if offset != 0 {
		return Event{}, errors.New("timestamp debe estar expresado en UTC")
	}

	event := Event{
		EventID:     strings.TrimSpace(wire.EventID),
		Timestamp:   timestamp.UTC(),
		GameID:      wire.GameID,
		GameVersion: strings.TrimSpace(wire.GameVersion),
		MatchID:     strings.TrimSpace(wire.MatchID),
		Sequence:    wire.Sequence,
		PlayerID:    wire.PlayerID,
		Type:        strings.TrimSpace(wire.Type),
		Data:        bytes.Clone(wire.Data),
	}
	if event.PlayerID != nil {
		trimmed := strings.TrimSpace(*event.PlayerID)
		event.PlayerID = &trimmed
	}
	if err := event.ValidateEnvelope(); err != nil {
		return Event{}, err
	}
	return event, nil
}

func (e Event) ValidateEnvelope() error {
	switch {
	case e.EventID == "":
		return errors.New("eventId no puede estar vacio")
	case e.Timestamp.IsZero():
		return errors.New("timestamp no puede estar vacio")
	case e.GameVersion == "":
		return errors.New("gameVersion no puede estar vacio")
	case e.MatchID == "":
		return errors.New("matchId no puede estar vacio")
	case e.Sequence <= 0:
		return errors.New("sequence debe ser mayor que cero")
	case e.PlayerID != nil && *e.PlayerID == "":
		return errors.New("playerId debe ser null o un identificador no vacio")
	case e.Type == "":
		return errors.New("type no puede estar vacio")
	}
	switch e.GameID {
	case GameChess, GameBasketball, GameRacing:
	default:
		return fmt.Errorf("gameId %q no esta permitido", e.GameID)
	}
	trimmedData := bytes.TrimSpace(e.Data)
	if len(trimmedData) == 0 || bytes.Equal(trimmedData, []byte("null")) || trimmedData[0] != '{' {
		return errors.New("data debe ser un objeto JSON")
	}
	var object map[string]json.RawMessage
	if err := json.Unmarshal(trimmedData, &object); err != nil {
		return fmt.Errorf("data debe ser un objeto JSON valido: %w", err)
	}
	return nil
}

func DecodeData(raw json.RawMessage, target any, required ...string) error {
	var fields map[string]json.RawMessage
	if err := decodeStrict(raw, &fields); err != nil {
		return fmt.Errorf("data invalido: %w", err)
	}
	for _, field := range required {
		if _, ok := fields[field]; !ok {
			return fmt.Errorf("data no contiene el campo obligatorio %q", field)
		}
	}
	if err := decodeStrict(raw, target); err != nil {
		return fmt.Errorf("data invalido: %w", err)
	}
	return nil
}

func decodeStrict(data []byte, target any) error {
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(target); err != nil {
		return err
	}
	if err := decoder.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
		if err == nil {
			return errors.New("se encontro contenido adicional")
		}
		return err
	}
	return nil
}
