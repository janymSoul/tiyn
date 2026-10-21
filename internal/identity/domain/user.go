package domain

import (
	"strings"
	"time"
	"unicode/utf8"

	"github.com/google/uuid"
)

const maxDisplayNameLength = 60

type User struct {
	id          uuid.UUID
	googleSub   string
	role        Role
	displayName string
	joinCode    JoinCode
	createdAt   time.Time
}

func NewUser(id uuid.UUID, googleSub string, role Role, displayName string, codes JoinCodeSource) (User, error) {
	if googleSub == "" {
		return User{}, ErrInvalidSubject
	}
	if _, err := ParseRole(string(role)); err != nil {
		return User{}, err
	}
	name, err := normaliseDisplayName(displayName)
	if err != nil {
		return User{}, err
	}
	user := User{id: id, googleSub: googleSub, role: role, displayName: name}
	if role == RoleParent {
		code, err := codes.Next()
		if err != nil {
			return User{}, err
		}
		user.joinCode = code
	}
	return user, nil
}

func RehydrateUser(id uuid.UUID, googleSub string, role Role, displayName, joinCode string, createdAt time.Time) User {
	return User{id: id, googleSub: googleSub, role: role, displayName: displayName, joinCode: JoinCode(joinCode), createdAt: createdAt}
}

func normaliseDisplayName(value string) (string, error) {
	name := strings.TrimSpace(value)
	if length := utf8.RuneCountInString(name); length == 0 || length > maxDisplayNameLength {
		return "", ErrInvalidDisplayName
	}
	return name, nil
}

func (u User) ID() uuid.UUID        { return u.id }
func (u User) GoogleSub() string    { return u.googleSub }
func (u User) Role() Role           { return u.role }
func (u User) DisplayName() string  { return u.displayName }
func (u User) CreatedAt() time.Time { return u.createdAt }
func (u User) IsParent() bool       { return u.role == RoleParent }
func (u User) IsTeen() bool         { return u.role == RoleTeen }

func (u User) JoinCode() (JoinCode, bool) {
	return u.joinCode, u.joinCode != ""
}
