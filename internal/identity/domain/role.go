package domain

type Role string

const (
	RoleParent Role = "PARENT"
	RoleTeen   Role = "TEEN"
)

func ParseRole(value string) (Role, error) {
	switch role := Role(value); role {
	case RoleParent, RoleTeen:
		return role, nil
	default:
		return "", ErrInvalidRole
	}
}
