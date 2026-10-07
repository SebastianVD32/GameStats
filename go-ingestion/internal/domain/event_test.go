package domain

import (
	"strings"
	"testing"
)

func TestDecodeEvent(t *testing.T) {
	t.Parallel()
	raw := `{
		"eventId":"event-1","timestamp":"2026-09-01T12:00:00Z",
		"gameId":"CHESS","gameVersion":"1.0","matchId":"match-1",
		"sequence":1,"playerId":null,"type":"MATCH_STARTED","data":{}
	}`

	event, err := DecodeEvent([]byte(raw))
	if err != nil {
		t.Fatalf("DecodeEvent() error = %v", err)
	}
	if event.EventID != "event-1" || event.GameID != GameChess || event.PlayerID != nil {
		t.Fatalf("evento inesperado: %#v", event)
	}
}

func TestDecodeEventRejectsInvalidEnvelope(t *testing.T) {
	t.Parallel()
	tests := map[string]struct {
		replace string
		want    string
	}{
		"missing playerId":  {`"playerId":null,`, "playerId"},
		"non UTC timestamp": {`"timestamp":"2026-09-01T12:00:00Z"`, "UTC"},
		"unknown game":      {`"gameId":"CHESS"`, "no esta permitido"},
		"unknown field":     {`"data":{}`, `unknown field`},
	}
	base := `{"eventId":"event-1","timestamp":"2026-09-01T12:00:00Z","gameId":"CHESS","gameVersion":"1.0","matchId":"match-1","sequence":1,"playerId":null,"type":"MATCH_STARTED","data":{}}`
	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			t.Parallel()
			input := base
			switch name {
			case "missing playerId":
				input = strings.Replace(input, test.replace, "", 1)
			case "non UTC timestamp":
				input = strings.Replace(input, test.replace, `"timestamp":"2026-09-01T06:00:00-06:00"`, 1)
			case "unknown game":
				input = strings.Replace(input, test.replace, `"gameId":"SOCCER"`, 1)
			case "unknown field":
				input = strings.Replace(input, test.replace, `"data":{},"extra":true`, 1)
			}
			_, err := DecodeEvent([]byte(input))
			if err == nil || !strings.Contains(err.Error(), test.want) {
				t.Fatalf("DecodeEvent() error = %v, se esperaba texto %q", err, test.want)
			}
		})
	}
}

func TestDecodeDataRequiresFieldsAndRejectsUnknownFields(t *testing.T) {
	t.Parallel()
	var data ShotData
	if err := DecodeData([]byte(`{"teamId":"a"}`), &data, "teamId", "made"); err == nil {
		t.Fatal("DecodeData() debio rechazar un campo obligatorio ausente")
	}
	if err := DecodeData([]byte(`{"teamId":"a","period":1,"periodTimeRemainingMs":1,"shotValue":2,"made":true,"extra":1}`), &data); err == nil {
		t.Fatal("DecodeData() debio rechazar un campo desconocido")
	}
}
