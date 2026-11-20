package ingestion

import (
	"context"
	"testing"

	"github.com/SebastianVD32/GameStats/go-ingestion/internal/domain"
)

func TestRacingAbortDeliveryIsTransactionalAndClosesSummary(t *testing.T) {
	sink := &recordingSink{failNext: true}
	p, err := NewProcessor(2, sink)
	if err != nil {
		t.Fatal(err)
	}
	defer p.Close()
	ctx := context.Background()
	start := event(domain.GameRacing, "aborted", "start-aborted", 1, nil, domain.EventMatchStarted,
		domain.RacingStartData{DriverIDs: []string{"a", "b"}, PlannedLaps: 3})
	if _, err := p.Process(ctx, start); err != nil {
		t.Fatal(err)
	}
	abort := event(domain.GameRacing, "aborted", "end-aborted", 2, nil, domain.EventMatchAborted,
		domain.RacingAbortData{Reason: "USER_QUIT"})
	if _, err := p.Process(ctx, abort); CodeOf(err) != CodeDeliveryFailed {
		t.Fatalf("delivery error = %v", err)
	}
	before, _ := p.Summary(ctx)
	if before.Totals.ActiveMatches != 1 {
		t.Fatal("failed delivery closed match")
	}
	if result, err := p.Process(ctx, abort); err != nil || !result.Completed || !result.Delivered {
		t.Fatalf("retry = %v, %v", result, err)
	}
	after, _ := p.Summary(ctx)
	if after.Totals.ActiveMatches != 0 || after.Totals.FinishedMatches != 1 {
		t.Fatalf("summary = %+v", after)
	}
	if _, err := p.Process(ctx, abort); CodeOf(err) != CodeDuplicate {
		t.Fatal("retry was not deduplicated")
	}
	if len(sink.batches) != 1 {
		t.Fatal("duplicate batch delivered")
	}
}
