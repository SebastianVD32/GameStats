package httpapi

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strings"

	"github.com/SebastianVD32/GameStats/go-ingestion/internal/domain"
	"github.com/SebastianVD32/GameStats/go-ingestion/internal/ingestion"
)

const (
	maxRequestBytes = 4 << 20
	maxBatchEvents  = 1_000
)

type eventProcessor interface {
	Process(context.Context, domain.Event) (ingestion.Outcome, error)
	Match(context.Context, string) (domain.MatchBatch, error)
}

type Handler struct {
	processor eventProcessor
	mux       *http.ServeMux
}

func NewHandler(processor eventProcessor) *Handler {
	handler := &Handler{processor: processor, mux: http.NewServeMux()}
	handler.mux.HandleFunc("GET /healthz", handler.health)
	handler.mux.HandleFunc("POST /api/v1/events", handler.createEvent)
	handler.mux.HandleFunc("POST /api/v1/events/batch", handler.createBatch)
	handler.mux.HandleFunc("GET /api/v1/matches/{matchId}", handler.getMatch)
	return handler
}

func (h *Handler) ServeHTTP(writer http.ResponseWriter, request *http.Request) {
	writer.Header().Set("X-Content-Type-Options", "nosniff")
	h.mux.ServeHTTP(writer, request)
}

func (h *Handler) health(writer http.ResponseWriter, _ *http.Request) {
	writeJSON(writer, http.StatusOK, map[string]string{"status": "ok"})
}

func (h *Handler) createEvent(writer http.ResponseWriter, request *http.Request) {
	raw, err := readBody(writer, request)
	if err != nil {
		writeProblem(writer, http.StatusBadRequest, "INVALID_JSON", err.Error())
		return
	}
	event, err := domain.DecodeEvent(raw)
	if err != nil {
		writeProblem(writer, http.StatusBadRequest, "INVALID_JSON", err.Error())
		return
	}
	outcome, err := h.processor.Process(request.Context(), event)
	if err != nil {
		writeProcessError(writer, err)
		return
	}
	writeJSON(writer, http.StatusCreated, outcome)
}

func (h *Handler) createBatch(writer http.ResponseWriter, request *http.Request) {
	raw, err := readBody(writer, request)
	if err != nil {
		writeProblem(writer, http.StatusBadRequest, "INVALID_JSON", err.Error())
		return
	}
	var encodedEvents []json.RawMessage
	decoder := json.NewDecoder(strings.NewReader(string(raw)))
	if err := decoder.Decode(&encodedEvents); err != nil {
		writeProblem(writer, http.StatusBadRequest, "INVALID_JSON", "el cuerpo debe ser un arreglo JSON de eventos")
		return
	}
	if err := ensureEOF(decoder); err != nil {
		writeProblem(writer, http.StatusBadRequest, "INVALID_JSON", err.Error())
		return
	}
	if len(encodedEvents) == 0 || len(encodedEvents) > maxBatchEvents {
		writeProblem(writer, http.StatusBadRequest, "INVALID_BATCH", "el lote debe contener entre 1 y 1000 eventos")
		return
	}

	results := make([]batchResult, 0, len(encodedEvents))
	allAccepted := true
	for index, rawEvent := range encodedEvents {
		event, err := domain.DecodeEvent(rawEvent)
		if err != nil {
			allAccepted = false
			results = append(results, batchResult{Index: index, Error: &problem{Code: "INVALID_JSON", Message: err.Error()}})
			continue
		}
		outcome, err := h.processor.Process(request.Context(), event)
		if err != nil {
			allAccepted = false
			results = append(results, batchResult{Index: index, EventID: event.EventID, Error: processProblem(err)})
			continue
		}
		results = append(results, batchResult{Index: index, EventID: event.EventID, Outcome: &outcome})
	}
	status := http.StatusCreated
	if !allAccepted {
		status = http.StatusMultiStatus
	}
	writeJSON(writer, status, map[string]any{"results": results})
}

func (h *Handler) getMatch(writer http.ResponseWriter, request *http.Request) {
	matchID := strings.TrimSpace(request.PathValue("matchId"))
	if matchID == "" {
		writeProblem(writer, http.StatusBadRequest, "INVALID_MATCH_ID", "matchId no puede estar vacio")
		return
	}
	batch, err := h.processor.Match(request.Context(), matchID)
	if err != nil {
		writeProcessError(writer, err)
		return
	}
	writeJSON(writer, http.StatusOK, batch)
}

type problem struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

type batchResult struct {
	Index   int                `json:"index"`
	EventID string             `json:"eventId,omitempty"`
	Outcome *ingestion.Outcome `json:"outcome,omitempty"`
	Error   *problem           `json:"error,omitempty"`
}

func readBody(writer http.ResponseWriter, request *http.Request) ([]byte, error) {
	request.Body = http.MaxBytesReader(writer, request.Body, maxRequestBytes)
	data, err := io.ReadAll(request.Body)
	if err != nil {
		return nil, err
	}
	if len(strings.TrimSpace(string(data))) == 0 {
		return nil, errors.New("el cuerpo de la solicitud no puede estar vacio")
	}
	return data, nil
}

func ensureEOF(decoder *json.Decoder) error {
	if err := decoder.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
		return errors.New("se encontro contenido adicional despues del JSON")
	}
	return nil
}

func writeProcessError(writer http.ResponseWriter, err error) {
	status := http.StatusInternalServerError
	switch ingestion.CodeOf(err) {
	case ingestion.CodeInvalid:
		status = http.StatusUnprocessableEntity
	case ingestion.CodeDuplicate:
		status = http.StatusConflict
	case ingestion.CodeDeliveryFailed:
		status = http.StatusBadGateway
	case ingestion.CodeMatchNotFound:
		status = http.StatusNotFound
	case ingestion.CodeProcessorClosed:
		status = http.StatusServiceUnavailable
	}
	item := processProblem(err)
	writeJSON(writer, status, item)
}

func processProblem(err error) *problem {
	code := ingestion.CodeOf(err)
	if code == "" {
		code = "INTERNAL_ERROR"
	}
	return &problem{Code: string(code), Message: err.Error()}
}

func writeProblem(writer http.ResponseWriter, status int, code, message string) {
	writeJSON(writer, status, problem{Code: code, Message: message})
}

func writeJSON(writer http.ResponseWriter, status int, value any) {
	writer.Header().Set("Content-Type", "application/json; charset=utf-8")
	writer.WriteHeader(status)
	_ = json.NewEncoder(writer).Encode(value)
}
