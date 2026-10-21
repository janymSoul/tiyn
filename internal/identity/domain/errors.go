package domain

import "errors"

var (
	ErrInvalidRole        = errors.New("role must be PARENT or TEEN")
	ErrInvalidDisplayName = errors.New("display name must be between 1 and 60 characters")
	ErrInvalidSubject     = errors.New("google subject is required")
	ErrInvalidJoinCode    = errors.New("invalid join code")
	ErrNotParent          = errors.New("user is not a parent")
	ErrNotTeen            = errors.New("user is not a teen")
)
