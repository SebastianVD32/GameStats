package eventsource

import (
	"os"
	"path/filepath"
	"testing"
)

const validEvent = `{"eventId":"start-1","timestamp":"2026-09-01T12:00:00Z","gameId":"RACING","gameVersion":"1.0","matchId":"race-1","sequence":1,"playerId":null,"type":"MATCH_STARTED","data":{"driverIds":["d1","d2"],"plannedLaps":1}}`

func TestLoadFileSupportsArrayAndJSONLines(t *testing.T) {
	t.Parallel()
	for name, contents := range map[string]string{
		"array":     "[" + validEvent + "]",
		"jsonlines": validEvent + "\n",
	} {
		t.Run(name, func(t *testing.T) {
			t.Parallel()
			path := filepath.Join(t.TempDir(), "events.json")
			if err := os.WriteFile(path, []byte(contents), 0o600); err != nil {
				t.Fatal(err)
			}
			events, err := LoadFile(path)
			if err != nil {
				t.Fatalf("LoadFile() error = %v", err)
			}
			if len(events) != 1 || events[0].MatchID != "race-1" {
				t.Fatalf("eventos inesperados: %#v", events)
			}
		})
	}
}
