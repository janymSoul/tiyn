package idhttp_test

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/stretchr/testify/require"

	"tiyn/internal/identity"
	idhttp "tiyn/internal/identity/http"
	"tiyn/internal/platform/db"
	"tiyn/internal/platform/httpx"
	"tiyn/internal/platform/testdb"
)

func TestMain(m *testing.M) {
	testdb.Main(m, identity.Migrations)
}

type fakeGoogle struct {
	mu         sync.Mutex
	identities map[string]identity.GoogleIdentity
}

func (f *fakeGoogle) Verify(_ context.Context, token string) (identity.GoogleIdentity, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	found, ok := f.identities[token]
	if !ok {
		return identity.GoogleIdentity{}, errors.New("token rejected")
	}
	return found, nil
}

type api struct {
	t      *testing.T
	server *httptest.Server
	google *fakeGoogle
}

func newAPI(t *testing.T) *api {
	t.Helper()
	pool := testdb.New(t)
	tokens, err := identity.NewTokenIssuer("0123456789abcdef0123456789abcdef", time.Hour, time.Now)
	require.NoError(t, err)
	google := &fakeGoogle{identities: map[string]identity.GoogleIdentity{}}
	service := identity.NewService(db.NewStore(pool), tokens, google)
	router := httpx.NewRouter(pool.Ping, idhttp.Routes(service, slog.New(slog.DiscardHandler)))
	server := httptest.NewServer(router)
	t.Cleanup(server.Close)
	return &api{t: t, server: server, google: google}
}

type response struct {
	status int
	header http.Header
	body   map[string]any
}

func (a *api) call(method, path, token string, body any) response {
	a.t.Helper()
	var reader *bytes.Reader
	switch typed := body.(type) {
	case nil:
		reader = bytes.NewReader(nil)
	case string:
		reader = bytes.NewReader([]byte(typed))
	default:
		encoded, err := json.Marshal(typed)
		require.NoError(a.t, err)
		reader = bytes.NewReader(encoded)
	}
	request, err := http.NewRequest(method, a.server.URL+path, reader)
	require.NoError(a.t, err)
	if token != "" {
		request.Header.Set("Authorization", "Bearer "+token)
	}
	result, err := http.DefaultClient.Do(request)
	require.NoError(a.t, err)
	defer result.Body.Close()
	decoded := map[string]any{}
	_ = json.NewDecoder(result.Body).Decode(&decoded)
	return response{status: result.StatusCode, header: result.Header, body: decoded}
}

func (a *api) signIn(subject, role string) (token string, user map[string]any) {
	a.t.Helper()
	a.google.mu.Lock()
	a.google.identities["id-token-"+subject] = identity.GoogleIdentity{Subject: subject, Name: "Name of " + subject}
	a.google.mu.Unlock()
	result := a.call("POST", "/auth/google", "", map[string]string{"idToken": "id-token-" + subject, "role": role})
	require.Equal(a.t, http.StatusOK, result.status, result.body)
	return result.body["token"].(string), result.body["user"].(map[string]any)
}

func TestSignInReturnsATokenAndTheAccountWithAJoinCodeForParents(t *testing.T) {
	a := newAPI(t)

	token, user := a.signIn("g-parent", "PARENT")

	require.NotEmpty(t, token)
	require.Equal(t, "PARENT", user["role"])
	require.Len(t, user["joinCode"], 6)
	require.Equal(t, "Name of g-parent", user["displayName"])
	_, err := uuid.Parse(user["id"].(string))
	require.NoError(t, err)
}

func TestSignInOfATeenOmitsTheJoinCode(t *testing.T) {
	a := newAPI(t)

	_, user := a.signIn("g-teen", "TEEN")

	require.NotContains(t, user, "joinCode")
}

func TestSignInRejectsAGoogleTokenWeCannotVerify(t *testing.T) {
	a := newAPI(t)

	result := a.call("POST", "/auth/google", "", map[string]string{"idToken": "forged", "role": "PARENT"})

	require.Equal(t, http.StatusUnauthorized, result.status)
	require.Equal(t, "invalid_identity", result.body["error"])
	require.Equal(t, "Bearer", result.header.Get("WWW-Authenticate"))
}

func TestFirstSignInWithoutARoleIsRejectedAsInvalid(t *testing.T) {
	a := newAPI(t)
	a.google.identities["t"] = identity.GoogleIdentity{Subject: "g", Name: "N"}

	result := a.call("POST", "/auth/google", "", map[string]string{"idToken": "t"})

	require.Equal(t, http.StatusUnprocessableEntity, result.status)
	require.Equal(t, "invalid_role", result.body["error"])
}

func TestSignInRejectsMalformedBodies(t *testing.T) {
	a := newAPI(t)
	cases := map[string]string{
		"not json":      "{",
		"unknown field": `{"idToken":"t","role":"TEEN","admin":true}`,
		"trailing data": `{"idToken":"t","role":"TEEN"} {"x":1}`,
		"wrong type":    `{"idToken":5}`,
		"empty body":    "",
	}
	for name, body := range cases {
		t.Run(name, func(t *testing.T) {
			result := a.call("POST", "/auth/google", "", body)

			require.Equal(t, http.StatusBadRequest, result.status)
			require.Equal(t, "malformed_body", result.body["error"])
		})
	}
}

func TestSignInRejectsAnOversizedBody(t *testing.T) {
	a := newAPI(t)
	huge := `{"idToken":"` + strings.Repeat("a", 2<<20) + `"}`

	result := a.call("POST", "/auth/google", "", huge)

	require.Equal(t, http.StatusRequestEntityTooLarge, result.status)
	require.Equal(t, "payload_too_large", result.body["error"])
}

func TestProtectedRoutesRequireAValidBearerToken(t *testing.T) {
	a := newAPI(t)
	routes := []struct{ method, path string }{
		{"GET", "/me"},
		{"POST", "/links/join"},
		{"DELETE", "/links/" + uuid.NewString()},
	}
	for _, route := range routes {
		for name, token := range map[string]string{"no token": "", "garbage": "garbage"} {
			result := a.call(route.method, route.path, token, map[string]string{"code": "K7M4QX"})

			require.Equal(t, http.StatusUnauthorized, result.status, route.method+" "+route.path+" "+name)
			require.Equal(t, "unauthenticated", result.body["error"])
			require.Equal(t, "Bearer", result.header.Get("WWW-Authenticate"))
		}
	}
}

func TestAuthorizationHeaderMustUseTheBearerScheme(t *testing.T) {
	a := newAPI(t)
	token, _ := a.signIn("g-parent", "PARENT")
	request, err := http.NewRequest("GET", a.server.URL+"/me", nil)
	require.NoError(t, err)
	request.Header.Set("Authorization", "Basic "+token)

	result, err := http.DefaultClient.Do(request)
	require.NoError(t, err)
	defer result.Body.Close()

	require.Equal(t, http.StatusUnauthorized, result.StatusCode)
}

func TestMeReturnsTheCallersAccount(t *testing.T) {
	a := newAPI(t)
	token, user := a.signIn("g-parent", "PARENT")

	result := a.call("GET", "/me", token, nil)

	require.Equal(t, http.StatusOK, result.status)
	require.Equal(t, user, result.body)
}

func TestTeenJoinsAParentAndSeesTheirName(t *testing.T) {
	a := newAPI(t)
	_, parent := a.signIn("g-parent", "PARENT")
	teenToken, _ := a.signIn("g-teen", "TEEN")

	result := a.call("POST", "/links/join", teenToken, map[string]string{"code": parent["joinCode"].(string)})

	require.Equal(t, http.StatusOK, result.status)
	require.Equal(t, map[string]any{"parent": map[string]any{"displayName": "Name of g-parent"}}, result.body)
}

func TestJoinWithAWrongOrMalformedCodeIsRejectedUniformly(t *testing.T) {
	a := newAPI(t)
	a.signIn("g-parent", "PARENT")
	teenToken, _ := a.signIn("g-teen", "TEEN")

	for _, code := range []string{"ZZZZZZ", "", "bad", "K7M4Q0"} {
		result := a.call("POST", "/links/join", teenToken, map[string]string{"code": code})

		require.Equal(t, http.StatusUnprocessableEntity, result.status, code)
		require.Equal(t, "invalid_join_code", result.body["error"])
	}
}

func TestAParentCannotJoinWithACode(t *testing.T) {
	a := newAPI(t)
	_, parent := a.signIn("g-parent", "PARENT")
	otherToken, _ := a.signIn("g-other", "PARENT")

	result := a.call("POST", "/links/join", otherToken, map[string]string{"code": parent["joinCode"].(string)})

	require.Equal(t, http.StatusForbidden, result.status)
	require.Equal(t, "forbidden", result.body["error"])
}

func TestParentRemovesALinkAndRepeatingItIsHarmless(t *testing.T) {
	a := newAPI(t)
	parentToken, parent := a.signIn("g-parent", "PARENT")
	teenToken, teen := a.signIn("g-teen", "TEEN")
	a.call("POST", "/links/join", teenToken, map[string]string{"code": parent["joinCode"].(string)})
	path := "/links/" + teen["id"].(string)

	first := a.call("DELETE", path, parentToken, nil)
	second := a.call("DELETE", path, parentToken, nil)

	require.Equal(t, http.StatusNoContent, first.status)
	require.Equal(t, http.StatusNoContent, second.status)
}

func TestRemovingALinkThatNeverExistedIsNotFoundAndATeenCannotRemoveOne(t *testing.T) {
	a := newAPI(t)
	parentToken, _ := a.signIn("g-parent", "PARENT")
	teenToken, teen := a.signIn("g-teen", "TEEN")
	path := "/links/" + teen["id"].(string)

	missing := a.call("DELETE", path, parentToken, nil)
	forbidden := a.call("DELETE", path, teenToken, nil)

	require.Equal(t, http.StatusNotFound, missing.status)
	require.Equal(t, "not_found", missing.body["error"])
	require.Equal(t, http.StatusForbidden, forbidden.status)
}

func TestRemovingALinkWithAnInvalidIDIsABadRequest(t *testing.T) {
	a := newAPI(t)
	parentToken, _ := a.signIn("g-parent", "PARENT")

	result := a.call("DELETE", "/links/not-a-uuid", parentToken, nil)

	require.Equal(t, http.StatusBadRequest, result.status)
	require.Equal(t, "invalid_teen_id", result.body["error"])
}

func TestHealthEndpointsStillWorkAlongsideTheIdentityRoutes(t *testing.T) {
	a := newAPI(t)

	for _, path := range []string{"/healthz", "/readyz"} {
		response, err := http.Get(a.server.URL + path)
		require.NoError(t, err)
		response.Body.Close()
		require.Equal(t, http.StatusOK, response.StatusCode, path)
	}
}

func TestUnknownRoutesAreNotFound(t *testing.T) {
	a := newAPI(t)

	response, err := http.Get(a.server.URL + "/nope")
	require.NoError(t, err)
	response.Body.Close()

	require.Equal(t, http.StatusNotFound, response.StatusCode)
}
