package identity

import (
	"context"
	"errors"

	"github.com/google/uuid"

	"tiyn/internal/identity/domain"
)

var (
	ErrUnauthenticated = errors.New("not authenticated")
	ErrForbidden       = errors.New("not allowed")
	ErrNotFound        = errors.New("not found")
	ErrInvalidIdentity = errors.New("invalid google identity")
)

type Principal struct {
	UserID uuid.UUID
	Role   domain.Role
}

type Authenticator interface {
	Verify(ctx context.Context, bearer string) (Principal, error)
}

type FamilyDirectory interface {
	AreLinked(ctx context.Context, parentID, teenID uuid.UUID) (bool, error)
}

type principalKey struct{}

func WithPrincipal(ctx context.Context, principal Principal) context.Context {
	return context.WithValue(ctx, principalKey{}, principal)
}

func PrincipalFrom(ctx context.Context) (Principal, bool) {
	principal, ok := ctx.Value(principalKey{}).(Principal)
	return principal, ok
}
