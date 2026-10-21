package identity_test

import (
	"strings"
	"testing"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/google/uuid"
	"github.com/stretchr/testify/require"

	"tiyn/internal/identity"
	"tiyn/internal/identity/domain"
)

const testSecret = "0123456789abcdef0123456789abcdef"

var epoch = time.Date(2026, 10, 22, 12, 0, 0, 0, time.UTC)

type clock struct{ now time.Time }

func (c *clock) Now() time.Time { return c.now }

func newIssuer(t *testing.T, c *clock) *identity.TokenIssuer {
	t.Helper()
	issuer, err := identity.NewTokenIssuer(testSecret, 7*24*time.Hour, c.Now)
	require.NoError(t, err)
	return issuer
}

func TestIssuedTokenVerifiesToTheSamePrincipal(t *testing.T) {
	issuer := newIssuer(t, &clock{epoch})
	userID := uuid.New()

	token, err := issuer.Issue(userID, domain.RoleTeen)
	require.NoError(t, err)
	principal, err := issuer.Verify(token)

	require.NoError(t, err)
	require.Equal(t, identity.Principal{UserID: userID, Role: domain.RoleTeen}, principal)
}

func TestTokenIsValidUntilItsLifetimeEnds(t *testing.T) {
	c := &clock{epoch}
	issuer := newIssuer(t, c)
	token, err := issuer.Issue(uuid.New(), domain.RoleParent)
	require.NoError(t, err)

	c.now = epoch.Add(7*24*time.Hour - time.Minute)
	_, err = issuer.Verify(token)
	require.NoError(t, err)

	c.now = epoch.Add(7*24*time.Hour + time.Minute)
	_, err = issuer.Verify(token)
	require.ErrorIs(t, err, identity.ErrUnauthenticated)
}

func TestTokenSignedWithAnotherSecretIsRejected(t *testing.T) {
	other, err := identity.NewTokenIssuer("fedcba9876543210fedcba9876543210", time.Hour, (&clock{epoch}).Now)
	require.NoError(t, err)
	token, err := other.Issue(uuid.New(), domain.RoleParent)
	require.NoError(t, err)

	_, err = newIssuer(t, &clock{epoch}).Verify(token)

	require.ErrorIs(t, err, identity.ErrUnauthenticated)
}

func TestTamperedTokenIsRejected(t *testing.T) {
	issuer := newIssuer(t, &clock{epoch})
	token, err := issuer.Issue(uuid.New(), domain.RoleTeen)
	require.NoError(t, err)
	parts := strings.Split(token, ".")
	parts[1] = parts[1][:len(parts[1])-2] + "AA"

	_, err = issuer.Verify(strings.Join(parts, "."))

	require.ErrorIs(t, err, identity.ErrUnauthenticated)
}

func signWith(t *testing.T, method jwt.SigningMethod, key any, claims jwt.MapClaims) string {
	t.Helper()
	token, err := jwt.NewWithClaims(method, claims).SignedString(key)
	require.NoError(t, err)
	return token
}

func validClaims() jwt.MapClaims {
	return jwt.MapClaims{
		"iss": "tiyn", "sub": uuid.NewString(), "role": "TEEN",
		"iat": epoch.Unix(), "exp": epoch.Add(time.Hour).Unix(),
	}
}

func TestTokensWithOtherAlgorithmsOrNoSignatureAreRejected(t *testing.T) {
	issuer := newIssuer(t, &clock{epoch})

	unsigned := signWith(t, jwt.SigningMethodNone, jwt.UnsafeAllowNoneSignatureType, validClaims())
	_, err := issuer.Verify(unsigned)
	require.ErrorIs(t, err, identity.ErrUnauthenticated)

	hs512 := signWith(t, jwt.SigningMethodHS512, []byte(testSecret), validClaims())
	_, err = issuer.Verify(hs512)
	require.ErrorIs(t, err, identity.ErrUnauthenticated)

	_, err = issuer.Verify(signWith(t, jwt.SigningMethodHS256, []byte(testSecret), validClaims()))
	require.NoError(t, err)
}

func TestTokensMissingOrWrongClaimsAreRejected(t *testing.T) {
	issuer := newIssuer(t, &clock{epoch})
	mutations := map[string]func(jwt.MapClaims){
		"no expiry":      func(c jwt.MapClaims) { delete(c, "exp") },
		"wrong issuer":   func(c jwt.MapClaims) { c["iss"] = "someone-else" },
		"no issuer":      func(c jwt.MapClaims) { delete(c, "iss") },
		"no subject":     func(c jwt.MapClaims) { delete(c, "sub") },
		"subject not id": func(c jwt.MapClaims) { c["sub"] = "not-a-uuid" },
		"no role":        func(c jwt.MapClaims) { delete(c, "role") },
		"unknown role":   func(c jwt.MapClaims) { c["role"] = "ADMIN" },
	}
	for name, mutate := range mutations {
		t.Run(name, func(t *testing.T) {
			claims := validClaims()
			mutate(claims)

			_, err := issuer.Verify(signWith(t, jwt.SigningMethodHS256, []byte(testSecret), claims))

			require.ErrorIs(t, err, identity.ErrUnauthenticated)
		})
	}
}

func TestGarbageIsRejected(t *testing.T) {
	issuer := newIssuer(t, &clock{epoch})
	for _, garbage := range []string{"", "abc", "a.b.c", "Bearer x"} {
		_, err := issuer.Verify(garbage)
		require.ErrorIs(t, err, identity.ErrUnauthenticated, garbage)
	}
}

func TestIssuerRejectsAWeakSecretAndANonPositiveLifetime(t *testing.T) {
	_, err := identity.NewTokenIssuer("too-short", time.Hour, time.Now)
	require.ErrorContains(t, err, "at least 32 bytes")

	_, err = identity.NewTokenIssuer(testSecret, 0, time.Now)
	require.ErrorContains(t, err, "ttl")
}
