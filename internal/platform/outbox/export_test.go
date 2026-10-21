package outbox

import (
	"context"
	"time"
)

func DeliverForTest(relay *Relay, ctx context.Context, id int64) error {
	return relay.deliver(ctx, id, relay.publisher.Topics())
}

func RecordFailureForTest(relay *Relay, ctx context.Context, id int64) error {
	return relay.recordFailure(ctx, id)
}

func LastUnhandledCheck(relay *Relay) time.Time {
	return relay.lastUnhandledWarning
}
