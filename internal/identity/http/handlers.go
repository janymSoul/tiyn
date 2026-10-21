package idhttp

import (
	"errors"
	"log/slog"
	"net/http"
	"strings"

	"github.com/go-chi/chi/v5"
	"github.com/google/uuid"

	"tiyn/internal/identity"
	"tiyn/internal/identity/domain"
	"tiyn/internal/platform/httpx"
)

type handler struct {
	service *identity.Service
	logger  *slog.Logger
}

type userBody struct {
	ID          uuid.UUID `json:"id"`
	Role        string    `json:"role"`
	DisplayName string    `json:"displayName"`
	JoinCode    string    `json:"joinCode,omitempty"`
}

type signInRequest struct {
	IDToken     string `json:"idToken"`
	Role        string `json:"role"`
	DisplayName string `json:"displayName"`
}

type signInResponse struct {
	Token string   `json:"token"`
	User  userBody `json:"user"`
}

type joinRequest struct {
	Code string `json:"code"`
}

type joinResponse struct {
	Parent struct {
		DisplayName string `json:"displayName"`
	} `json:"parent"`
}

func Routes(service *identity.Service, logger *slog.Logger) func(chi.Router) {
	h := &handler{service: service, logger: logger}
	return func(router chi.Router) {
		router.Post("/auth/google", h.signIn)
		router.Group(func(authenticated chi.Router) {
			authenticated.Use(Authenticated(service))
			authenticated.Get("/me", h.me)
			authenticated.Post("/links/join", h.join)
			authenticated.Delete("/links/{teenId}", h.removeLink)
		})
	}
}

func Authenticated(authenticator identity.Authenticator) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			token, ok := bearerToken(r)
			if !ok {
				unauthenticated(w)
				return
			}
			principal, err := authenticator.Verify(r.Context(), token)
			if err != nil {
				unauthenticated(w)
				return
			}
			next.ServeHTTP(w, r.WithContext(identity.WithPrincipal(r.Context(), principal)))
		})
	}
}

func bearerToken(r *http.Request) (string, bool) {
	scheme, token, found := strings.Cut(r.Header.Get("Authorization"), " ")
	if !found || !strings.EqualFold(scheme, "Bearer") || strings.TrimSpace(token) == "" {
		return "", false
	}
	return strings.TrimSpace(token), true
}

func unauthenticated(w http.ResponseWriter) {
	w.Header().Set("WWW-Authenticate", "Bearer")
	httpx.WriteError(w, http.StatusUnauthorized, "unauthenticated")
}

func (h *handler) signIn(w http.ResponseWriter, r *http.Request) {
	var request signInRequest
	if !h.decode(w, r, &request) {
		return
	}
	result, err := h.service.SignInWithGoogle(r.Context(), identity.SignInInput{
		IDToken: request.IDToken, Role: request.Role, DisplayName: request.DisplayName,
	})
	if err != nil {
		h.fail(w, err)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, signInResponse{Token: result.Token, User: toUserBody(result.User)})
}

func (h *handler) me(w http.ResponseWriter, r *http.Request) {
	principal, _ := identity.PrincipalFrom(r.Context())
	user, err := h.service.Me(r.Context(), principal)
	if err != nil {
		h.fail(w, err)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, toUserBody(user))
}

func (h *handler) join(w http.ResponseWriter, r *http.Request) {
	var request joinRequest
	if !h.decode(w, r, &request) {
		return
	}
	principal, _ := identity.PrincipalFrom(r.Context())
	result, err := h.service.JoinWithCode(r.Context(), principal, request.Code)
	if err != nil {
		h.fail(w, err)
		return
	}
	var response joinResponse
	response.Parent.DisplayName = result.ParentDisplayName
	httpx.WriteJSON(w, http.StatusOK, response)
}

func (h *handler) removeLink(w http.ResponseWriter, r *http.Request) {
	teenID, err := uuid.Parse(chi.URLParam(r, "teenId"))
	if err != nil {
		httpx.WriteError(w, http.StatusBadRequest, "invalid_teen_id")
		return
	}
	principal, _ := identity.PrincipalFrom(r.Context())
	if err := h.service.RemoveLink(r.Context(), principal, teenID); err != nil {
		h.fail(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (h *handler) decode(w http.ResponseWriter, r *http.Request, destination any) bool {
	err := httpx.DecodeJSON(w, r, destination)
	switch {
	case err == nil:
		return true
	case errors.Is(err, httpx.ErrPayloadTooLarge):
		httpx.WriteError(w, http.StatusRequestEntityTooLarge, "payload_too_large")
	default:
		httpx.WriteError(w, http.StatusBadRequest, "malformed_body")
	}
	return false
}

func (h *handler) fail(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, identity.ErrInvalidIdentity):
		w.Header().Set("WWW-Authenticate", "Bearer")
		httpx.WriteError(w, http.StatusUnauthorized, "invalid_identity")
	case errors.Is(err, identity.ErrUnauthenticated):
		unauthenticated(w)
	case errors.Is(err, identity.ErrForbidden):
		httpx.WriteError(w, http.StatusForbidden, "forbidden")
	case errors.Is(err, identity.ErrNotFound):
		httpx.WriteError(w, http.StatusNotFound, "not_found")
	case errors.Is(err, domain.ErrInvalidJoinCode):
		httpx.WriteError(w, http.StatusUnprocessableEntity, "invalid_join_code")
	case errors.Is(err, domain.ErrInvalidRole):
		httpx.WriteError(w, http.StatusUnprocessableEntity, "invalid_role")
	case errors.Is(err, domain.ErrInvalidDisplayName):
		httpx.WriteError(w, http.StatusUnprocessableEntity, "invalid_display_name")
	default:
		h.logger.Error("identity request failed", "err", err)
		httpx.WriteError(w, http.StatusInternalServerError, "internal")
	}
}

func toUserBody(user identity.UserView) userBody {
	return userBody{ID: user.ID, Role: string(user.Role), DisplayName: user.DisplayName, JoinCode: user.JoinCode}
}
