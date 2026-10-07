package eventsource

import (
	"bufio"
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"

	"github.com/SebastianVD32/GameStats/go-ingestion/internal/domain"
)

const maxInputBytes = 32 << 20

func LoadFile(path string) ([]domain.Event, error) {
	info, err := os.Stat(path)
	if err != nil {
		return nil, fmt.Errorf("consultar archivo de entrada: %w", err)
	}
	if info.Size() > maxInputBytes {
		return nil, fmt.Errorf("el archivo supera el limite de %d bytes", maxInputBytes)
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("leer archivo de entrada: %w", err)
	}
	trimmed := bytes.TrimSpace(data)
	if len(trimmed) == 0 {
		return nil, errors.New("el archivo de entrada esta vacio")
	}
	if trimmed[0] == '[' {
		return decodeArray(trimmed)
	}
	return decodeJSONLines(trimmed)
}

func decodeArray(data []byte) ([]domain.Event, error) {
	var rawEvents []json.RawMessage
	decoder := json.NewDecoder(bytes.NewReader(data))
	if err := decoder.Decode(&rawEvents); err != nil {
		return nil, fmt.Errorf("decodificar arreglo JSON: %w", err)
	}
	if err := decoder.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
		return nil, errors.New("se encontro contenido adicional despues del arreglo JSON")
	}
	if len(rawEvents) == 0 {
		return nil, errors.New("el arreglo de eventos esta vacio")
	}
	return decodeRawEvents(rawEvents)
}

func decodeJSONLines(data []byte) ([]domain.Event, error) {
	scanner := bufio.NewScanner(bytes.NewReader(data))
	scanner.Buffer(make([]byte, 64<<10), 1<<20)
	events := make([]domain.Event, 0)
	for line := 1; scanner.Scan(); line++ {
		raw := bytes.TrimSpace(scanner.Bytes())
		if len(raw) == 0 {
			continue
		}
		event, err := domain.DecodeEvent(raw)
		if err != nil {
			return nil, fmt.Errorf("linea %d: %w", line, err)
		}
		events = append(events, event)
	}
	if err := scanner.Err(); err != nil {
		return nil, fmt.Errorf("leer JSON Lines: %w", err)
	}
	if len(events) == 0 {
		return nil, errors.New("el archivo no contiene eventos")
	}
	return events, nil
}

func decodeRawEvents(rawEvents []json.RawMessage) ([]domain.Event, error) {
	events := make([]domain.Event, 0, len(rawEvents))
	for index, raw := range rawEvents {
		event, err := domain.DecodeEvent(raw)
		if err != nil {
			return nil, fmt.Errorf("evento %d: %w", index, err)
		}
		events = append(events, event)
	}
	return events, nil
}
