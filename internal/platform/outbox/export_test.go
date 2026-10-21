package outbox

import "context"

func DeliverForTest(relay *Relay, ctx context.Context, id int64) error {
	return relay.deliver(ctx, id)
}
