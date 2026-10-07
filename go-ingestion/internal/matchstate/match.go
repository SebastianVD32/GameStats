package matchstate

import (
	"encoding/json"
	"errors"
	"fmt"
	"regexp"
	"strings"
	"time"

	"github.com/SebastianVD32/GameStats/go-ingestion/internal/domain"
)

const (
	eventMovePlayed        = "MOVE_PLAYED"
	eventPieceCaptured     = "PIECE_CAPTURED"
	eventShotAttempted     = "SHOT_ATTEMPTED"
	eventAssistRecorded    = "ASSIST_RECORDED"
	eventReboundCollected  = "REBOUND_COLLECTED"
	eventTurnoverCommitted = "TURNOVER_COMMITTED"
	eventFoulCommitted     = "FOUL_COMMITTED"
	eventLapCompleted      = "LAP_COMPLETED"
	eventPitEntered        = "PIT_ENTERED"
	eventPitExited         = "PIT_EXITED"
	eventPenaltyApplied    = "PENALTY_APPLIED"
)

type Match struct {
	gameID       domain.GameID
	gameVersion  string
	matchID      string
	lastSequence int64
	lastTime     time.Time
	finished     bool
	events       []domain.Event
	chess        *chessState
	basketball   *basketballState
	racing       *racingState
}

func Start(event domain.Event) (*Match, error) {
	if event.Type != domain.EventMatchStarted {
		return nil, errors.New("la primera accion de una partida debe ser MATCH_STARTED")
	}
	if event.Sequence != 1 {
		return nil, errors.New("MATCH_STARTED debe tener sequence igual a 1")
	}
	if event.PlayerID != nil {
		return nil, errors.New("MATCH_STARTED debe tener playerId null")
	}

	match := &Match{
		gameID:       event.GameID,
		gameVersion:  event.GameVersion,
		matchID:      event.MatchID,
		lastSequence: event.Sequence,
		lastTime:     event.Timestamp,
		events:       []domain.Event{cloneEvent(event)},
	}
	var err error
	switch event.GameID {
	case domain.GameChess:
		match.chess, err = startChess(event.Data)
	case domain.GameBasketball:
		match.basketball, err = startBasketball(event.Data)
	case domain.GameRacing:
		match.racing, err = startRacing(event.Data)
	default:
		err = fmt.Errorf("gameId %q no esta soportado", event.GameID)
	}
	if err != nil {
		return nil, fmt.Errorf("MATCH_STARTED invalido: %w", err)
	}
	return match, nil
}

func (m *Match) Apply(event domain.Event) (bool, error) {
	if m.finished {
		return false, errors.New("la partida ya finalizo")
	}
	if event.MatchID != m.matchID {
		return false, errors.New("matchId no coincide con la partida")
	}
	if event.GameID != m.gameID {
		return false, errors.New("gameId no coincide con MATCH_STARTED")
	}
	if event.GameVersion != m.gameVersion {
		return false, errors.New("gameVersion no coincide con MATCH_STARTED")
	}
	if event.Sequence != m.lastSequence+1 {
		return false, fmt.Errorf("sequence debe ser %d para mantener una secuencia continua", m.lastSequence+1)
	}
	if event.Timestamp.Before(m.lastTime) {
		return false, errors.New("timestamp no puede ser anterior al evento previo de la partida")
	}
	if event.Type == domain.EventMatchStarted {
		return false, errors.New("la partida ya contiene MATCH_STARTED")
	}

	if event.Type == domain.EventMatchFinished {
		if event.PlayerID != nil {
			return false, errors.New("MATCH_FINISHED debe tener playerId null")
		}
		if err := m.validateFinish(event.Data); err != nil {
			return false, fmt.Errorf("MATCH_FINISHED invalido: %w", err)
		}
		m.finished = true
	} else {
		if event.PlayerID == nil {
			return false, fmt.Errorf("%s debe identificar al jugador en playerId", event.Type)
		}
		if err := m.validateAction(event); err != nil {
			return false, err
		}
	}

	m.lastSequence = event.Sequence
	m.lastTime = event.Timestamp
	m.events = append(m.events, cloneEvent(event))
	return m.finished, nil
}

func (m *Match) validateAction(event domain.Event) error {
	switch m.gameID {
	case domain.GameChess:
		return m.chess.apply(event)
	case domain.GameBasketball:
		return m.basketball.apply(event)
	case domain.GameRacing:
		return m.racing.apply(event)
	default:
		return fmt.Errorf("gameId %q no esta soportado", m.gameID)
	}
}

func (m *Match) validateFinish(raw json.RawMessage) error {
	switch m.gameID {
	case domain.GameChess:
		return m.chess.finish(raw)
	case domain.GameBasketball:
		return m.basketball.finish(raw)
	case domain.GameRacing:
		return m.racing.finish(raw)
	default:
		return fmt.Errorf("gameId %q no esta soportado", m.gameID)
	}
}

func (m *Match) Batch() domain.MatchBatch {
	events := make([]domain.Event, len(m.events))
	for i, event := range m.events {
		events[i] = cloneEvent(event)
	}
	return domain.MatchBatch{
		GameID:      m.gameID,
		GameVersion: m.gameVersion,
		MatchID:     m.matchID,
		Events:      events,
	}
}

func (m *Match) Finished() bool { return m.finished }

func (m *Match) Clone() *Match {
	clone := *m
	clone.events = make([]domain.Event, len(m.events))
	for i, event := range m.events {
		clone.events[i] = cloneEvent(event)
	}
	if m.chess != nil {
		clone.chess = m.chess.clone()
	}
	if m.basketball != nil {
		clone.basketball = m.basketball.clone()
	}
	if m.racing != nil {
		clone.racing = m.racing.clone()
	}
	return &clone
}

func cloneEvent(event domain.Event) domain.Event {
	clone := event
	clone.Data = append(json.RawMessage(nil), event.Data...)
	if event.PlayerID != nil {
		playerID := *event.PlayerID
		clone.PlayerID = &playerID
	}
	return clone
}

type chessMove struct {
	playerID string
	data     domain.ChessMoveData
}

type chessState struct {
	colorByPlayer map[string]string
	playerByColor map[string]string
	clockByPlayer map[string]int64
	incrementMs   int64
	nextMove      int64
	moves         map[string]chessMove
	capturedMoves map[string]bool
}

func startChess(raw json.RawMessage) (*chessState, error) {
	var data domain.ChessStartData
	if err := domain.DecodeData(raw, &data, "participants", "initialClockMs", "incrementMs"); err != nil {
		return nil, err
	}
	if len(data.Participants) != 2 {
		return nil, errors.New("participants debe contener exactamente dos jugadores")
	}
	if data.InitialClockMs <= 0 || data.IncrementMs < 0 {
		return nil, errors.New("initialClockMs debe ser positivo e incrementMs no puede ser negativo")
	}
	state := &chessState{
		colorByPlayer: make(map[string]string, 2),
		playerByColor: make(map[string]string, 2),
		clockByPlayer: make(map[string]int64, 2),
		incrementMs:   data.IncrementMs,
		nextMove:      1,
		moves:         make(map[string]chessMove),
		capturedMoves: make(map[string]bool),
	}
	for _, participant := range data.Participants {
		playerID := strings.TrimSpace(participant.PlayerID)
		color := strings.ToUpper(strings.TrimSpace(participant.Color))
		if playerID == "" || (color != "WHITE" && color != "BLACK") {
			return nil, errors.New("cada participante requiere playerId y color WHITE o BLACK")
		}
		if _, exists := state.colorByPlayer[playerID]; exists {
			return nil, errors.New("los jugadores de ajedrez deben ser distintos")
		}
		if _, exists := state.playerByColor[color]; exists {
			return nil, fmt.Errorf("el color %s aparece mas de una vez", color)
		}
		state.colorByPlayer[playerID] = color
		state.playerByColor[color] = playerID
		state.clockByPlayer[playerID] = data.InitialClockMs
	}
	if len(state.playerByColor) != 2 {
		return nil, errors.New("debe existir un jugador WHITE y uno BLACK")
	}
	return state, nil
}

var chessSquare = regexp.MustCompile(`^[a-h][1-8]$`)

func (s *chessState) apply(event domain.Event) error {
	playerID := *event.PlayerID
	if _, ok := s.colorByPlayer[playerID]; !ok {
		return fmt.Errorf("playerId %q no participa en la partida", playerID)
	}
	switch event.Type {
	case eventMovePlayed:
		var data domain.ChessMoveData
		if err := domain.DecodeData(event.Data, &data, "moveNumber", "piece", "from", "to", "decisionTimeMs", "clockBeforeMs", "clockAfterMs"); err != nil {
			return err
		}
		if data.MoveNumber != s.nextMove {
			return fmt.Errorf("moveNumber debe ser %d", s.nextMove)
		}
		expectedColor := "WHITE"
		if data.MoveNumber%2 == 0 {
			expectedColor = "BLACK"
		}
		if s.colorByPlayer[playerID] != expectedColor {
			return fmt.Errorf("el movimiento %d corresponde al jugador %s", data.MoveNumber, expectedColor)
		}
		if !validChessPiece(data.Piece, true) {
			return fmt.Errorf("piece %q no esta permitido", data.Piece)
		}
		if !chessSquare.MatchString(data.From) || !chessSquare.MatchString(data.To) || data.From == data.To {
			return errors.New("from y to deben ser casillas distintas entre a1 y h8")
		}
		if data.DecisionTimeMs < 0 || data.ClockBeforeMs <= 0 || data.ClockAfterMs < 0 {
			return errors.New("los valores de reloj no pueden ser negativos y clockBeforeMs debe ser positivo")
		}
		if data.ClockBeforeMs != s.clockByPlayer[playerID] {
			return fmt.Errorf("clockBeforeMs debe ser %d", s.clockByPlayer[playerID])
		}
		if data.DecisionTimeMs >= data.ClockBeforeMs {
			return errors.New("un movimiento no puede completarse si el jugador agoto su reloj")
		}
		expectedAfter := data.ClockBeforeMs - data.DecisionTimeMs + s.incrementMs
		if data.ClockAfterMs != expectedAfter {
			return fmt.Errorf("clockAfterMs debe ser %d al aplicar el incremento", expectedAfter)
		}
		s.clockByPlayer[playerID] = data.ClockAfterMs
		s.moves[event.EventID] = chessMove{playerID: playerID, data: data}
		s.nextMove++
		return nil
	case eventPieceCaptured:
		var data domain.ChessCaptureData
		if err := domain.DecodeData(event.Data, &data, "moveEventId", "capturedPiece", "capturedPlayerId"); err != nil {
			return err
		}
		move, ok := s.moves[data.MoveEventID]
		if !ok {
			return fmt.Errorf("moveEventId %q no referencia un movimiento previo", data.MoveEventID)
		}
		if move.playerID != playerID {
			return errors.New("la captura debe pertenecer al jugador que realizo el movimiento")
		}
		if s.capturedMoves[data.MoveEventID] {
			return errors.New("el movimiento ya tiene una captura asociada")
		}
		if !validChessPiece(data.CapturedPiece, false) {
			return fmt.Errorf("capturedPiece %q no esta permitido", data.CapturedPiece)
		}
		capturedPlayerID := strings.TrimSpace(data.CapturedPlayerID)
		if _, ok := s.colorByPlayer[capturedPlayerID]; !ok || capturedPlayerID == playerID {
			return errors.New("capturedPlayerId debe identificar al oponente")
		}
		s.capturedMoves[data.MoveEventID] = true
		return nil
	default:
		return fmt.Errorf("type %q no esta permitido para CHESS", event.Type)
	}
}

func (s *chessState) finish(raw json.RawMessage) error {
	var data domain.ChessFinishData
	if err := domain.DecodeData(raw, &data, "winnerPlayerId", "reason"); err != nil {
		return err
	}
	reason := strings.ToUpper(strings.TrimSpace(data.Reason))
	switch reason {
	case "DRAW":
		if data.WinnerPlayerID != nil {
			return errors.New("winnerPlayerId debe ser null cuando reason es DRAW")
		}
	case "CHECKMATE", "RESIGNATION", "TIMEOUT":
		if data.WinnerPlayerID == nil {
			return fmt.Errorf("winnerPlayerId es obligatorio cuando reason es %s", reason)
		}
		if _, ok := s.colorByPlayer[*data.WinnerPlayerID]; !ok {
			return errors.New("winnerPlayerId no participa en la partida")
		}
	default:
		return fmt.Errorf("reason %q no esta permitido", data.Reason)
	}
	return nil
}

func validChessPiece(piece string, includeKing bool) bool {
	switch strings.ToUpper(strings.TrimSpace(piece)) {
	case "QUEEN", "ROOK", "BISHOP", "KNIGHT", "PAWN":
		return true
	case "KING":
		return includeKing
	default:
		return false
	}
}

func (s *chessState) clone() *chessState {
	clone := *s
	clone.colorByPlayer = copyMap(s.colorByPlayer)
	clone.playerByColor = copyMap(s.playerByColor)
	clone.clockByPlayer = copyMap(s.clockByPlayer)
	clone.moves = copyMap(s.moves)
	clone.capturedMoves = copyMap(s.capturedMoves)
	return &clone
}

type recordedShot struct {
	playerID string
	data     domain.ShotData
}

type basketballState struct {
	periods        int
	teamByPlayer   map[string]string
	playersByTeam  map[string]map[string]bool
	scores         map[string]int
	fouls          map[string]int
	shots          map[string]recordedShot
	assistedShots  map[string]bool
	reboundedShots map[string]bool
}

func startBasketball(raw json.RawMessage) (*basketballState, error) {
	var data domain.BasketballStartData
	if err := domain.DecodeData(raw, &data, "teams", "periods"); err != nil {
		return nil, err
	}
	if len(data.Teams) != 2 || data.Periods <= 0 {
		return nil, errors.New("se requieren exactamente dos equipos y periods debe ser positivo")
	}
	state := &basketballState{
		periods:        data.Periods,
		teamByPlayer:   make(map[string]string),
		playersByTeam:  make(map[string]map[string]bool, 2),
		scores:         make(map[string]int, 2),
		fouls:          make(map[string]int),
		shots:          make(map[string]recordedShot),
		assistedShots:  make(map[string]bool),
		reboundedShots: make(map[string]bool),
	}
	for _, team := range data.Teams {
		teamID := strings.TrimSpace(team.TeamID)
		if teamID == "" || len(team.PlayerIDs) == 0 {
			return nil, errors.New("cada equipo requiere teamId y al menos un jugador")
		}
		if _, exists := state.playersByTeam[teamID]; exists {
			return nil, fmt.Errorf("teamId %q esta repetido", teamID)
		}
		state.playersByTeam[teamID] = make(map[string]bool)
		state.scores[teamID] = 0
		for _, rawPlayerID := range team.PlayerIDs {
			playerID := strings.TrimSpace(rawPlayerID)
			if playerID == "" {
				return nil, errors.New("playerIds no puede contener identificadores vacios")
			}
			if _, exists := state.teamByPlayer[playerID]; exists {
				return nil, fmt.Errorf("playerId %q aparece mas de una vez", playerID)
			}
			state.teamByPlayer[playerID] = teamID
			state.playersByTeam[teamID][playerID] = true
			state.fouls[playerID] = 0
		}
	}
	return state, nil
}

func (s *basketballState) apply(event domain.Event) error {
	playerID := *event.PlayerID
	teamID, ok := s.teamByPlayer[playerID]
	if !ok {
		return fmt.Errorf("playerId %q no participa en la partida", playerID)
	}
	if s.fouls[playerID] >= 5 {
		return fmt.Errorf("playerId %q fue excluido al alcanzar cinco faltas", playerID)
	}
	switch event.Type {
	case eventShotAttempted:
		var data domain.ShotData
		if err := domain.DecodeData(event.Data, &data, "teamId", "period", "periodTimeRemainingMs", "shotValue", "made"); err != nil {
			return err
		}
		if err := s.validateTeamPeriod(teamID, data.TeamID, data.Period); err != nil {
			return err
		}
		if data.PeriodTimeRemainingMs < 0 {
			return errors.New("periodTimeRemainingMs no puede ser negativo")
		}
		if data.ShotValue < 1 || data.ShotValue > 3 {
			return errors.New("shotValue debe ser 1, 2 o 3")
		}
		s.shots[event.EventID] = recordedShot{playerID: playerID, data: data}
		if data.Made {
			s.scores[teamID] += data.ShotValue
		}
		return nil
	case eventAssistRecorded:
		var data domain.AssistData
		if err := domain.DecodeData(event.Data, &data, "teamId", "period", "scorerId", "shotEventId"); err != nil {
			return err
		}
		if err := s.validateTeamPeriod(teamID, data.TeamID, data.Period); err != nil {
			return err
		}
		shot, exists := s.shots[data.ShotEventID]
		if !exists {
			return errors.New("shotEventId no referencia un tiro previo")
		}
		if s.assistedShots[data.ShotEventID] {
			return errors.New("el tiro ya tiene una asistencia asociada")
		}
		if !shot.data.Made || shot.data.ShotValue == 1 {
			return errors.New("una asistencia requiere una canasta de campo encestada")
		}
		if shot.data.TeamID != teamID || shot.playerID != data.ScorerID || shot.playerID == playerID {
			return errors.New("la asistencia debe referenciar la canasta de campo de un companero")
		}
		if shot.data.Period != data.Period {
			return errors.New("la asistencia y el tiro deben pertenecer al mismo periodo")
		}
		s.assistedShots[data.ShotEventID] = true
		return nil
	case eventReboundCollected:
		var data domain.ReboundData
		if err := domain.DecodeData(event.Data, &data, "teamId", "period", "reboundType", "shotEventId"); err != nil {
			return err
		}
		if err := s.validateTeamPeriod(teamID, data.TeamID, data.Period); err != nil {
			return err
		}
		shot, exists := s.shots[data.ShotEventID]
		if !exists || shot.data.Made {
			return errors.New("un rebote debe referenciar un tiro fallado previo")
		}
		if s.reboundedShots[data.ShotEventID] {
			return errors.New("el tiro ya tiene un rebote asociado")
		}
		typeName := strings.ToUpper(strings.TrimSpace(data.ReboundType))
		if typeName != "OFFENSIVE" && typeName != "DEFENSIVE" {
			return errors.New("reboundType debe ser OFFENSIVE o DEFENSIVE")
		}
		if shot.data.Period != data.Period {
			return errors.New("el rebote y el tiro deben pertenecer al mismo periodo")
		}
		if (typeName == "OFFENSIVE" && shot.data.TeamID != teamID) || (typeName == "DEFENSIVE" && shot.data.TeamID == teamID) {
			return errors.New("reboundType no coincide con el equipo que realizo el tiro")
		}
		s.reboundedShots[data.ShotEventID] = true
		return nil
	case eventTurnoverCommitted:
		var data domain.TurnoverData
		if err := domain.DecodeData(event.Data, &data, "teamId", "period", "reason"); err != nil {
			return err
		}
		if err := s.validateTeamPeriod(teamID, data.TeamID, data.Period); err != nil {
			return err
		}
		if strings.TrimSpace(data.Reason) == "" {
			return errors.New("reason no puede estar vacio")
		}
		return nil
	case eventFoulCommitted:
		var data domain.FoulData
		if err := domain.DecodeData(event.Data, &data, "teamId", "period", "affectedPlayerId", "reason"); err != nil {
			return err
		}
		if err := s.validateTeamPeriod(teamID, data.TeamID, data.Period); err != nil {
			return err
		}
		affectedTeam, exists := s.teamByPlayer[data.AffectedPlayerID]
		if !exists || affectedTeam == teamID {
			return errors.New("affectedPlayerId debe identificar a un jugador rival")
		}
		if strings.TrimSpace(data.Reason) == "" {
			return errors.New("reason no puede estar vacio")
		}
		s.fouls[playerID]++
		return nil
	default:
		return fmt.Errorf("type %q no esta permitido para BASKETBALL", event.Type)
	}
}

func (s *basketballState) validateTeamPeriod(expectedTeam, actualTeam string, period int) error {
	if actualTeam != expectedTeam {
		return errors.New("teamId no corresponde al equipo del jugador")
	}
	if period < 1 || period > s.periods {
		return fmt.Errorf("period debe estar entre 1 y %d", s.periods)
	}
	return nil
}

func (s *basketballState) finish(raw json.RawMessage) error {
	var data domain.BasketballFinishData
	if err := domain.DecodeData(raw, &data, "scores"); err != nil {
		return err
	}
	if len(data.Scores) != len(s.scores) {
		return errors.New("scores debe contener un resultado por equipo")
	}
	seen := make(map[string]bool, len(data.Scores))
	for _, score := range data.Scores {
		expected, ok := s.scores[score.TeamID]
		if !ok || seen[score.TeamID] {
			return errors.New("scores contiene un equipo desconocido o repetido")
		}
		if score.Points != expected {
			return fmt.Errorf("el marcador de %s debe ser %d", score.TeamID, expected)
		}
		seen[score.TeamID] = true
	}
	return nil
}

func (s *basketballState) clone() *basketballState {
	clone := *s
	clone.teamByPlayer = copyMap(s.teamByPlayer)
	clone.playersByTeam = make(map[string]map[string]bool, len(s.playersByTeam))
	for teamID, players := range s.playersByTeam {
		clone.playersByTeam[teamID] = copyMap(players)
	}
	clone.scores = copyMap(s.scores)
	clone.fouls = copyMap(s.fouls)
	clone.shots = copyMap(s.shots)
	clone.assistedShots = copyMap(s.assistedShots)
	clone.reboundedShots = copyMap(s.reboundedShots)
	return &clone
}

type pitStop struct {
	playerID string
	lap      int
	entered  time.Time
	exited   bool
}

type racingState struct {
	plannedLaps    int
	drivers        map[string]bool
	completedLaps  map[string]int
	positionsByLap map[int]map[int]string
	pitStops       map[string]pitStop
}

func startRacing(raw json.RawMessage) (*racingState, error) {
	var data domain.RacingStartData
	if err := domain.DecodeData(raw, &data, "driverIds", "plannedLaps"); err != nil {
		return nil, err
	}
	if len(data.DriverIDs) < 2 || data.PlannedLaps <= 0 {
		return nil, errors.New("se requieren al menos dos pilotos y plannedLaps debe ser positivo")
	}
	state := &racingState{
		plannedLaps:    data.PlannedLaps,
		drivers:        make(map[string]bool, len(data.DriverIDs)),
		completedLaps:  make(map[string]int, len(data.DriverIDs)),
		positionsByLap: make(map[int]map[int]string),
		pitStops:       make(map[string]pitStop),
	}
	for _, rawDriverID := range data.DriverIDs {
		driverID := strings.TrimSpace(rawDriverID)
		if driverID == "" || state.drivers[driverID] {
			return nil, errors.New("driverIds debe contener identificadores unicos y no vacios")
		}
		state.drivers[driverID] = true
		state.completedLaps[driverID] = 0
	}
	return state, nil
}

func (s *racingState) apply(event domain.Event) error {
	driverID := *event.PlayerID
	if !s.drivers[driverID] {
		return fmt.Errorf("playerId %q no participa en la carrera", driverID)
	}
	switch event.Type {
	case eventLapCompleted:
		var data domain.LapData
		if err := domain.DecodeData(event.Data, &data, "lapNumber", "lapTimeMs", "position"); err != nil {
			return err
		}
		expectedLap := s.completedLaps[driverID] + 1
		if data.LapNumber != expectedLap || data.LapNumber > s.plannedLaps {
			return fmt.Errorf("lapNumber debe ser %d y no superar plannedLaps", expectedLap)
		}
		if data.LapTimeMs <= 0 {
			return errors.New("lapTimeMs debe ser positivo")
		}
		if data.Position < 1 || data.Position > len(s.drivers) {
			return fmt.Errorf("position debe estar entre 1 y %d", len(s.drivers))
		}
		if s.positionsByLap[data.LapNumber] == nil {
			s.positionsByLap[data.LapNumber] = make(map[int]string)
		}
		if other := s.positionsByLap[data.LapNumber][data.Position]; other != "" {
			return fmt.Errorf("position %d de la vuelta %d ya pertenece a %s", data.Position, data.LapNumber, other)
		}
		s.positionsByLap[data.LapNumber][data.Position] = driverID
		s.completedLaps[driverID] = data.LapNumber
		return nil
	case eventPitEntered:
		var data domain.PitEntryData
		if err := domain.DecodeData(event.Data, &data, "stopId", "lapNumber", "reason"); err != nil {
			return err
		}
		if strings.TrimSpace(data.StopID) == "" || strings.TrimSpace(data.Reason) == "" {
			return errors.New("stopId y reason no pueden estar vacios")
		}
		if err := s.validateLapNumber(data.LapNumber); err != nil {
			return err
		}
		if _, exists := s.pitStops[data.StopID]; exists {
			return errors.New("stopId ya fue utilizado en esta carrera")
		}
		s.pitStops[data.StopID] = pitStop{playerID: driverID, lap: data.LapNumber, entered: event.Timestamp}
		return nil
	case eventPitExited:
		var data domain.PitExitData
		if err := domain.DecodeData(event.Data, &data, "stopId", "lapNumber", "tireCompound"); err != nil {
			return err
		}
		if strings.TrimSpace(data.TireCompound) == "" {
			return errors.New("tireCompound no puede estar vacio")
		}
		if err := s.validateLapNumber(data.LapNumber); err != nil {
			return err
		}
		stop, exists := s.pitStops[data.StopID]
		if !exists || stop.exited {
			return errors.New("stopId no tiene una entrada a boxes pendiente")
		}
		if stop.playerID != driverID || data.LapNumber < stop.lap || event.Timestamp.Before(stop.entered) {
			return errors.New("la salida de boxes no coincide con su entrada")
		}
		stop.exited = true
		s.pitStops[data.StopID] = stop
		return nil
	case eventPenaltyApplied:
		var data domain.PenaltyData
		if err := domain.DecodeData(event.Data, &data, "penaltyType", "value", "reason", "lapNumber"); err != nil {
			return err
		}
		penaltyType := strings.ToUpper(strings.TrimSpace(data.PenaltyType))
		if penaltyType != "TIME" && penaltyType != "POSITION" {
			return errors.New("penaltyType debe ser TIME o POSITION")
		}
		if data.Value <= 0 || strings.TrimSpace(data.Reason) == "" {
			return errors.New("value debe ser positivo y reason no puede estar vacio")
		}
		return s.validateLapNumber(data.LapNumber)
	default:
		return fmt.Errorf("type %q no esta permitido para RACING", event.Type)
	}
}

func (s *racingState) validateLapNumber(lap int) error {
	if lap < 1 || lap > s.plannedLaps {
		return fmt.Errorf("lapNumber debe estar entre 1 y %d", s.plannedLaps)
	}
	return nil
}

func (s *racingState) finish(raw json.RawMessage) error {
	var data domain.RacingFinishData
	if err := domain.DecodeData(raw, &data, "classification"); err != nil {
		return err
	}
	for driverID, laps := range s.completedLaps {
		if laps != s.plannedLaps {
			return fmt.Errorf("el piloto %s completo %d de %d vueltas", driverID, laps, s.plannedLaps)
		}
	}
	for stopID, stop := range s.pitStops {
		if !stop.exited {
			return fmt.Errorf("la parada %s no tiene PIT_EXITED", stopID)
		}
	}
	if len(data.Classification) != len(s.drivers) {
		return errors.New("classification debe contener todos los pilotos")
	}
	seenDrivers := make(map[string]bool, len(s.drivers))
	seenPositions := make(map[int]bool, len(s.drivers))
	for _, entry := range data.Classification {
		if !s.drivers[entry.PlayerID] || seenDrivers[entry.PlayerID] {
			return errors.New("classification contiene un piloto desconocido o repetido")
		}
		if entry.Position < 1 || entry.Position > len(s.drivers) || seenPositions[entry.Position] {
			return errors.New("classification contiene una posicion invalida o repetida")
		}
		seenDrivers[entry.PlayerID] = true
		seenPositions[entry.Position] = true
	}
	return nil
}

func (s *racingState) clone() *racingState {
	clone := *s
	clone.drivers = copyMap(s.drivers)
	clone.completedLaps = copyMap(s.completedLaps)
	clone.positionsByLap = make(map[int]map[int]string, len(s.positionsByLap))
	for lap, positions := range s.positionsByLap {
		clone.positionsByLap[lap] = copyMap(positions)
	}
	clone.pitStops = copyMap(s.pitStops)
	return &clone
}

func copyMap[K comparable, V any](source map[K]V) map[K]V {
	destination := make(map[K]V, len(source))
	for key, value := range source {
		destination[key] = value
	}
	return destination
}
