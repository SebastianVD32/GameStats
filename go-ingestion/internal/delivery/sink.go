package delivery

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/SebastianVD32/GameStats/go-ingestion/internal/domain"
)

type Sink interface {
	Deliver(context.Context, domain.MatchBatch) error
}

type FileSink struct {
	path string
	mu   sync.Mutex
}

func NewFileSink(path string) (*FileSink, error) {
	path = strings.TrimSpace(path)
	if path == "" {
		return nil, errors.New("la ruta de salida no puede estar vacia")
	}
	return &FileSink{path: filepath.Clean(path)}, nil
}

func (s *FileSink) Deliver(ctx context.Context, batch domain.MatchBatch) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	s.mu.Lock()
	defer s.mu.Unlock()

	if err := os.MkdirAll(filepath.Dir(s.path), 0o750); err != nil {
		return fmt.Errorf("crear directorio de salida: %w", err)
	}
	file, err := os.OpenFile(s.path, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o640)
	if err != nil {
		return fmt.Errorf("abrir archivo de salida: %w", err)
	}
	encoder := json.NewEncoder(file)
	err = encoder.Encode(batch)
	closeErr := file.Close()
	if err != nil {
		return fmt.Errorf("escribir partida preparada: %w", err)
	}
	if closeErr != nil {
		return fmt.Errorf("cerrar archivo de salida: %w", closeErr)
	}
	return nil
}

type HTTPSink struct {
	endpoint string
	client   *http.Client
}

func NewHTTPSink(endpoint string, client *http.Client) (*HTTPSink, error) {
	parsed, err := url.Parse(strings.TrimSpace(endpoint))
	if err != nil || (parsed.Scheme != "http" && parsed.Scheme != "https") || parsed.Host == "" {
		return nil, errors.New("ANALYZER_URL debe ser una URL HTTP o HTTPS absoluta")
	}
	if client == nil {
		client = &http.Client{Timeout: 10 * time.Second}
	}
	return &HTTPSink{endpoint: parsed.String(), client: client}, nil
}

func (s *HTTPSink) Deliver(ctx context.Context, batch domain.MatchBatch) error {
	payload, err := json.Marshal(batch)
	if err != nil {
		return fmt.Errorf("serializar partida: %w", err)
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, s.endpoint, bytes.NewReader(payload))
	if err != nil {
		return fmt.Errorf("crear solicitud al analizador: %w", err)
	}
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("Accept", "application/json")

	response, err := s.client.Do(request)
	if err != nil {
		return fmt.Errorf("enviar partida al analizador: %w", err)
	}
	defer response.Body.Close()
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		body, _ := io.ReadAll(io.LimitReader(response.Body, 4<<10))
		return fmt.Errorf("el analizador respondio %s: %s", response.Status, strings.TrimSpace(string(body)))
	}
	return nil
}

type DiscardSink struct{}

func (DiscardSink) Deliver(context.Context, domain.MatchBatch) error { return nil }
