package httpx

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
)

const maxBodyBytes = 1 << 20

var (
	ErrBadRequest      = errors.New("malformed request body")
	ErrPayloadTooLarge = errors.New("request body too large")
)

type errorBody struct {
	Error string `json:"error"`
}

func DecodeJSON(w http.ResponseWriter, r *http.Request, destination any) error {
	decoder := json.NewDecoder(http.MaxBytesReader(w, r.Body, maxBodyBytes))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(destination); err != nil {
		var tooLarge *http.MaxBytesError
		if errors.As(err, &tooLarge) {
			return ErrPayloadTooLarge
		}
		return ErrBadRequest
	}
	if _, err := decoder.Token(); !errors.Is(err, io.EOF) {
		return ErrBadRequest
	}
	return nil
}

func WriteJSON(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(body)
}

func WriteError(w http.ResponseWriter, status int, code string) {
	WriteJSON(w, status, errorBody{Error: code})
}
