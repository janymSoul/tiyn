package identity

import (
	"context"
	"errors"
	"fmt"

	"google.golang.org/api/idtoken"
)

type GoogleIdentity struct {
	Subject string
	Name    string
}

type GoogleVerifier interface {
	Verify(ctx context.Context, idToken string) (GoogleIdentity, error)
}

type googleVerifier struct {
	validator *idtoken.Validator
	audiences []string
}

func NewGoogleVerifier(ctx context.Context, audiences []string) (GoogleVerifier, error) {
	if len(audiences) == 0 {
		return nil, errors.New("at least one google client id is required")
	}
	validator, err := idtoken.NewValidator(ctx)
	if err != nil {
		return nil, fmt.Errorf("create google token validator: %w", err)
	}
	return &googleVerifier{validator: validator, audiences: audiences}, nil
}

func (g *googleVerifier) Verify(ctx context.Context, idToken string) (GoogleIdentity, error) {
	for _, audience := range g.audiences {
		payload, err := g.validator.Validate(ctx, idToken, audience)
		if err != nil {
			continue
		}
		name, _ := payload.Claims["name"].(string)
		return GoogleIdentity{Subject: payload.Subject, Name: name}, nil
	}
	return GoogleIdentity{}, ErrInvalidIdentity
}
