package identity

import (
	"errors"
	"fmt"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/google/uuid"

	"tiyn/internal/identity/domain"
)

const (
	tokenIssuer    = "tiyn"
	minSecretBytes = 32
)

type tokenClaims struct {
	jwt.RegisteredClaims
	Role string `json:"role"`
}

type TokenIssuer struct {
	secret []byte
	ttl    time.Duration
	now    func() time.Time
}

func NewTokenIssuer(secret string, ttl time.Duration, now func() time.Time) (*TokenIssuer, error) {
	if len(secret) < minSecretBytes {
		return nil, fmt.Errorf("jwt secret must be at least %d bytes", minSecretBytes)
	}
	if ttl <= 0 {
		return nil, errors.New("jwt ttl must be positive")
	}
	return &TokenIssuer{secret: []byte(secret), ttl: ttl, now: now}, nil
}

func (t *TokenIssuer) Issue(userID uuid.UUID, role domain.Role) (string, error) {
	issuedAt := t.now()
	claims := tokenClaims{
		RegisteredClaims: jwt.RegisteredClaims{
			Issuer:    tokenIssuer,
			Subject:   userID.String(),
			IssuedAt:  jwt.NewNumericDate(issuedAt),
			ExpiresAt: jwt.NewNumericDate(issuedAt.Add(t.ttl)),
		},
		Role: string(role),
	}
	signed, err := jwt.NewWithClaims(jwt.SigningMethodHS256, claims).SignedString(t.secret)
	if err != nil {
		return "", fmt.Errorf("sign token: %w", err)
	}
	return signed, nil
}

func (t *TokenIssuer) Verify(token string) (Principal, error) {
	var claims tokenClaims
	_, err := jwt.ParseWithClaims(token, &claims,
		func(*jwt.Token) (any, error) { return t.secret, nil },
		jwt.WithValidMethods([]string{jwt.SigningMethodHS256.Alg()}),
		jwt.WithIssuer(tokenIssuer),
		jwt.WithExpirationRequired(),
		jwt.WithTimeFunc(t.now),
	)
	if err != nil {
		return Principal{}, ErrUnauthenticated
	}
	userID, err := uuid.Parse(claims.Subject)
	if err != nil {
		return Principal{}, ErrUnauthenticated
	}
	role, err := domain.ParseRole(claims.Role)
	if err != nil {
		return Principal{}, ErrUnauthenticated
	}
	return Principal{UserID: userID, Role: role}, nil
}
