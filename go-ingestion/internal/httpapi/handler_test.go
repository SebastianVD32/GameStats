package httpapi

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/SebastianVD32/GameStats/go-ingestion/internal/delivery"
	"github.com/SebastianVD32/GameStats/go-ingestion/internal/ingestion"
)

func TestCreateAndGetMatch(t *testing.T) {
	t.Parallel()
	processor, err := ingestion.NewProcessor(2, delivery.DiscardSink{})
	if err != nil {
		t.Fatal(err)
	}
	defer processor.Close()
	handler := NewHandler(processor)

	body := `{"eventId":"start-1","timestamp":"2026-09-01T12:00:00Z","gameId":"CHESS","gameVersion":"1.0","matchId":"match-1","sequence":1,"playerId":null,"type":"MATCH_STARTED","data":{"participants":[{"playerId":"white","color":"WHITE"},{"playerId":"black","color":"BLACK"}],"initialClockMs":60000,"incrementMs":0}}`
	request := httptest.NewRequest(http.MethodPost, "/api/v1/events", bytes.NewBufferString(body))
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	if response.Code != http.StatusCreated {
		t.Fatalf("POST status = %d, body = %s", response.Code, response.Body.String())
	}

	request = httptest.NewRequest(http.MethodGet, "/api/v1/matches/match-1", nil)
	response = httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	if response.Code != http.StatusOK {
		t.Fatalf("GET status = %d, body = %s", response.Code, response.Body.String())
	}
	var result struct {
		MatchID string `json:"matchId"`
		Events  []any  `json:"events"`
	}
	if err := json.NewDecoder(response.Body).Decode(&result); err != nil {
		t.Fatal(err)
	}
	if result.MatchID != "match-1" || len(result.Events) != 1 {
		t.Fatalf("respuesta inesperada: %#v", result)
	}
}

func TestDuplicateReturnsConflict(t *testing.T) {
	t.Parallel()
	processor, _ := ingestion.NewProcessor(1, delivery.DiscardSink{})
	defer processor.Close()
	handler := NewHandler(processor)
	body := `{"eventId":"same","timestamp":"2026-09-01T12:00:00Z","gameId":"CHESS","gameVersion":"1.0","matchId":"match-1","sequence":1,"playerId":null,"type":"MATCH_STARTED","data":{"participants":[{"playerId":"white","color":"WHITE"},{"playerId":"black","color":"BLACK"}],"initialClockMs":60000,"incrementMs":0}}`
	for index, want := range []int{http.StatusCreated, http.StatusConflict} {
		request := httptest.NewRequest(http.MethodPost, "/api/v1/events", bytes.NewBufferString(body))
		response := httptest.NewRecorder()
		handler.ServeHTTP(response, request)
		if response.Code != want {
			t.Fatalf("request %d status = %d, want %d", index, response.Code, want)
		}
	}
}

func TestBatchReportsPartialFailure(t *testing.T) {
	t.Parallel()
	processor, _ := ingestion.NewProcessor(1, delivery.DiscardSink{})
	defer processor.Close()
	handler := NewHandler(processor)
	valid := `{"eventId":"start-1","timestamp":"2026-09-01T12:00:00Z","gameId":"RACING","gameVersion":"1.0","matchId":"race-1","sequence":1,"playerId":null,"type":"MATCH_STARTED","data":{"driverIds":["d1","d2"],"plannedLaps":1}}`
	body := "[" + valid + `,{"bad":true}]`
	request := httptest.NewRequest(http.MethodPost, "/api/v1/events/batch", bytes.NewBufferString(body))
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	if response.Code != http.StatusMultiStatus {
		t.Fatalf("status = %d, body = %s", response.Code, response.Body.String())
	}
}
