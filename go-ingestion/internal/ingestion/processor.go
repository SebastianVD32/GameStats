package ingestion

import (
	"context"
	"errors"
	"fmt"
	"hash/fnv"
	"sort"
	"sync"
	"sync/atomic"

	"github.com/SebastianVD32/GameStats/go-ingestion/internal/delivery"
	"github.com/SebastianVD32/GameStats/go-ingestion/internal/domain"
	"github.com/SebastianVD32/GameStats/go-ingestion/internal/matchstate"
)

type ErrorCode string

const (
	CodeInvalid         ErrorCode = "INVALID_EVENT"
	CodeDuplicate       ErrorCode = "DUPLICATE_EVENT"
	CodeDeliveryFailed  ErrorCode = "DELIVERY_FAILED"
	CodeMatchNotFound   ErrorCode = "MATCH_NOT_FOUND"
	CodeProcessorClosed ErrorCode = "PROCESSOR_CLOSED"
)

type ProcessError struct {
	Code    ErrorCode `json:"code"`
	Message string    `json:"message"`
	Cause   error     `json:"-"`
}

func (e *ProcessError) Error() string {
	if e.Cause == nil {
		return e.Message
	}
	return fmt.Sprintf("%s: %v", e.Message, e.Cause)
}

func (e *ProcessError) Unwrap() error { return e.Cause }

func CodeOf(err error) ErrorCode {
	var processError *ProcessError
	if errors.As(err, &processError) {
		return processError.Code
	}
	return ""
}

type Outcome struct {
	EventID   string `json:"eventId"`
	MatchID   string `json:"matchId"`
	Accepted  bool   `json:"accepted"`
	Completed bool   `json:"completed"`
	Delivered bool   `json:"delivered"`
}

type processRequest struct {
	ctx      context.Context
	event    domain.Event
	response chan processResponse
}

type processResponse struct {
	outcome Outcome
	err     error
}

type queryRequest struct {
	matchID  string
	response chan queryResponse
}

type queryResponse struct {
	batch domain.MatchBatch
	err   error
}

type summaryRequest struct {
	response chan []matchstate.Info
}

type workerCommand struct {
	process *processRequest
	query   *queryRequest
	summary *summaryRequest
}

// GameSummary cuenta las instancias de un juego segun el estado de ingestion.
type GameSummary struct {
	GameID          domain.GameID `json:"gameId"`
	StartedMatches  int           `json:"startedMatches"`
	ActiveMatches   int           `json:"activeMatches"`
	FinishedMatches int           `json:"finishedMatches"`
	AcceptedEvents  int           `json:"acceptedEvents"`
}

type SummaryTotals struct {
	StartedMatches  int `json:"startedMatches"`
	ActiveMatches   int `json:"activeMatches"`
	FinishedMatches int `json:"finishedMatches"`
	AcceptedEvents  int `json:"acceptedEvents"`
}

// Summary es una vista operativa de las partidas que conoce este proceso:
// cuantas instancias se iniciaron, cuales siguen activas y su progreso.
type Summary struct {
	Games         []GameSummary     `json:"games"`
	Totals        SummaryTotals     `json:"totals"`
	ActiveMatches []matchstate.Info `json:"activeMatches"`
}

var summaryGames = []domain.GameID{domain.GameChess, domain.GamePacMan, domain.GameRacing, domain.GameBasketball}

type Processor struct {
	sink      delivery.Sink
	workers   []chan workerCommand
	seen      sync.Map
	done      chan struct{}
	waitGroup sync.WaitGroup
	closed    atomic.Bool
	closeOnce sync.Once
}

func NewProcessor(workerCount int, sink delivery.Sink) (*Processor, error) {
	if workerCount <= 0 {
		return nil, errors.New("workerCount debe ser positivo")
	}
	if sink == nil {
		return nil, errors.New("sink no puede ser nil")
	}
	processor := &Processor{
		sink:    sink,
		workers: make([]chan workerCommand, workerCount),
		done:    make(chan struct{}),
	}
	for index := range processor.workers {
		processor.workers[index] = make(chan workerCommand, 64)
		processor.waitGroup.Add(1)
		go processor.runWorker(processor.workers[index])
	}
	return processor, nil
}

func (p *Processor) Process(ctx context.Context, event domain.Event) (Outcome, error) {
	if p.closed.Load() {
		return Outcome{}, closedError()
	}
	response := make(chan processResponse, 1)
	request := workerCommand{process: &processRequest{ctx: ctx, event: event, response: response}}
	worker := p.workers[p.workerIndex(event.MatchID)]
	select {
	case worker <- request:
	case <-ctx.Done():
		return Outcome{}, ctx.Err()
	case <-p.done:
		return Outcome{}, closedError()
	}
	select {
	case result := <-response:
		return result.outcome, result.err
	case <-ctx.Done():
		return Outcome{}, ctx.Err()
	case <-p.done:
		return Outcome{}, closedError()
	}
}

func (p *Processor) Match(ctx context.Context, matchID string) (domain.MatchBatch, error) {
	if p.closed.Load() {
		return domain.MatchBatch{}, closedError()
	}
	response := make(chan queryResponse, 1)
	request := workerCommand{query: &queryRequest{matchID: matchID, response: response}}
	worker := p.workers[p.workerIndex(matchID)]
	select {
	case worker <- request:
	case <-ctx.Done():
		return domain.MatchBatch{}, ctx.Err()
	case <-p.done:
		return domain.MatchBatch{}, closedError()
	}
	select {
	case result := <-response:
		return result.batch, result.err
	case <-ctx.Done():
		return domain.MatchBatch{}, ctx.Err()
	case <-p.done:
		return domain.MatchBatch{}, closedError()
	}
}

func (p *Processor) Summary(ctx context.Context) (Summary, error) {
	if p.closed.Load() {
		return Summary{}, closedError()
	}
	responses := make([]chan []matchstate.Info, len(p.workers))
	for index, worker := range p.workers {
		responses[index] = make(chan []matchstate.Info, 1)
		select {
		case worker <- workerCommand{summary: &summaryRequest{response: responses[index]}}:
		case <-ctx.Done():
			return Summary{}, ctx.Err()
		case <-p.done:
			return Summary{}, closedError()
		}
	}
	byGame := make(map[domain.GameID]*GameSummary, len(summaryGames))
	summary := Summary{Games: make([]GameSummary, len(summaryGames)), ActiveMatches: []matchstate.Info{}}
	for index, gameID := range summaryGames {
		summary.Games[index] = GameSummary{GameID: gameID}
		byGame[gameID] = &summary.Games[index]
	}
	for _, response := range responses {
		var infos []matchstate.Info
		select {
		case infos = <-response:
		case <-ctx.Done():
			return Summary{}, ctx.Err()
		case <-p.done:
			return Summary{}, closedError()
		}
		for _, info := range infos {
			game := byGame[info.GameID]
			if game == nil {
				continue
			}
			game.StartedMatches++
			game.AcceptedEvents += info.Events
			if info.Finished {
				game.FinishedMatches++
			} else {
				game.ActiveMatches++
				summary.ActiveMatches = append(summary.ActiveMatches, info)
			}
		}
	}
	for _, game := range summary.Games {
		summary.Totals.StartedMatches += game.StartedMatches
		summary.Totals.ActiveMatches += game.ActiveMatches
		summary.Totals.FinishedMatches += game.FinishedMatches
		summary.Totals.AcceptedEvents += game.AcceptedEvents
	}
	sort.Slice(summary.ActiveMatches, func(i, j int) bool {
		return summary.ActiveMatches[i].StartedAt.After(summary.ActiveMatches[j].StartedAt)
	})
	return summary, nil
}

func (p *Processor) Close() {
	p.closeOnce.Do(func() {
		p.closed.Store(true)
		close(p.done)
		p.waitGroup.Wait()
	})
}

func (p *Processor) runWorker(commands <-chan workerCommand) {
	defer p.waitGroup.Done()
	matches := make(map[string]*matchstate.Match)
	for {
		select {
		case <-p.done:
			return
		case command := <-commands:
			if command.process != nil {
				outcome, err := p.processOne(command.process.ctx, matches, command.process.event)
				command.process.response <- processResponse{outcome: outcome, err: err}
				continue
			}
			if command.summary != nil {
				infos := make([]matchstate.Info, 0, len(matches))
				for _, match := range matches {
					infos = append(infos, match.Info())
				}
				command.summary.response <- infos
				continue
			}
			if command.query != nil {
				match := matches[command.query.matchID]
				if match == nil {
					command.query.response <- queryResponse{err: &ProcessError{Code: CodeMatchNotFound, Message: "partida no encontrada"}}
				} else {
					command.query.response <- queryResponse{batch: match.Batch()}
				}
			}
		}
	}
}

func (p *Processor) processOne(ctx context.Context, matches map[string]*matchstate.Match, event domain.Event) (Outcome, error) {
	outcome := Outcome{EventID: event.EventID, MatchID: event.MatchID}
	if err := event.ValidateEnvelope(); err != nil {
		return outcome, &ProcessError{Code: CodeInvalid, Message: "evento invalido", Cause: err}
	}
	if _, loaded := p.seen.LoadOrStore(event.EventID, struct{}{}); loaded {
		return outcome, &ProcessError{Code: CodeDuplicate, Message: "eventId ya fue procesado"}
	}
	keepEventID := false
	defer func() {
		if !keepEventID {
			p.seen.Delete(event.EventID)
		}
	}()

	var candidate *matchstate.Match
	var completed bool
	var err error
	if current := matches[event.MatchID]; current == nil {
		candidate, err = matchstate.Start(event)
	} else {
		candidate = current.Clone()
		completed, err = candidate.Apply(event)
	}
	if err != nil {
		return outcome, &ProcessError{Code: CodeInvalid, Message: "evento rechazado", Cause: err}
	}
	if completed {
		if err := p.sink.Deliver(ctx, candidate.Batch()); err != nil {
			return outcome, &ProcessError{Code: CodeDeliveryFailed, Message: "no se pudo entregar la partida preparada", Cause: err}
		}
		outcome.Delivered = true
	}
	matches[event.MatchID] = candidate
	keepEventID = true
	outcome.Accepted = true
	outcome.Completed = completed
	return outcome, nil
}

func (p *Processor) workerIndex(matchID string) int {
	hash := fnv.New32a()
	_, _ = hash.Write([]byte(matchID))
	return int(hash.Sum32() % uint32(len(p.workers)))
}

func closedError() error {
	return &ProcessError{Code: CodeProcessorClosed, Message: "el procesador esta cerrado"}
}
