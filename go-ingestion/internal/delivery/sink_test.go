package delivery

import (
	"bufio"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"

	"github.com/SebastianVD32/GameStats/go-ingestion/internal/domain"
)

func TestFileSinkWritesJSONLines(t *testing.T) {
	t.Parallel()
	path := filepath.Join(t.TempDir(), "nested", "matches.jsonl")
	sink, err := NewFileSink(path)
	if err != nil {
		t.Fatal(err)
	}
	batch := domain.MatchBatch{GameID: domain.GameChess, GameVersion: "1.0", MatchID: "m1"}
	if err := sink.Deliver(context.Background(), batch); err != nil {
		t.Fatalf("Deliver() error = %v", err)
	}
	file, err := os.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer file.Close()
	scanner := bufio.NewScanner(file)
	if !scanner.Scan() {
		t.Fatal("no se escribio una linea")
	}
	var decoded domain.MatchBatch
	if err := json.Unmarshal(scanner.Bytes(), &decoded); err != nil {
		t.Fatalf("linea JSON invalida: %v", err)
	}
	if decoded.MatchID != batch.MatchID {
		t.Fatalf("MatchID = %q, se esperaba %q", decoded.MatchID, batch.MatchID)
	}
}

func TestHTTPSink(t *testing.T) {
	t.Parallel()
	var received domain.MatchBatch
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		if request.Method != http.MethodPost || request.Header.Get("Content-Type") != "application/json" {
			t.Errorf("solicitud inesperada: %s, %s", request.Method, request.Header.Get("Content-Type"))
		}
		if err := json.NewDecoder(request.Body).Decode(&received); err != nil {
			t.Errorf("Decode() error = %v", err)
		}
		writer.WriteHeader(http.StatusAccepted)
	}))
	defer server.Close()

	sink, err := NewHTTPSink(server.URL, server.Client())
	if err != nil {
		t.Fatal(err)
	}
	if err := sink.Deliver(context.Background(), domain.MatchBatch{MatchID: "m1"}); err != nil {
		t.Fatalf("Deliver() error = %v", err)
	}
	if received.MatchID != "m1" {
		t.Fatalf("se recibio MatchID %q", received.MatchID)
	}
}
