package domain

import (
	"crypto/rand"
	"fmt"
	"math/big"
	"strings"
)

const (
	joinCodeAlphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
	joinCodeLength   = 6
)

type JoinCode string

type JoinCodeSource interface {
	Next() (JoinCode, error)
}

type RandomJoinCodes struct{}

func (RandomJoinCodes) Next() (JoinCode, error) {
	limit := big.NewInt(int64(len(joinCodeAlphabet)))
	code := make([]byte, joinCodeLength)
	for i := range code {
		index, err := rand.Int(rand.Reader, limit)
		if err != nil {
			return "", fmt.Errorf("generate join code: %w", err)
		}
		code[i] = joinCodeAlphabet[index.Int64()]
	}
	return JoinCode(code), nil
}

func ParseJoinCode(value string) (JoinCode, error) {
	normalised := strings.ToUpper(strings.TrimSpace(value))
	if len(normalised) != joinCodeLength {
		return "", ErrInvalidJoinCode
	}
	for _, character := range normalised {
		if !strings.ContainsRune(joinCodeAlphabet, character) {
			return "", ErrInvalidJoinCode
		}
	}
	return JoinCode(normalised), nil
}

func (c JoinCode) String() string {
	return string(c)
}
