package identity

import "github.com/google/uuid"

const (
	FamilyLinkedEventType   = "FamilyLinkedEvent"
	FamilyUnlinkedEventType = "FamilyUnlinkedEvent"
)

type FamilyLinkedEvent struct {
	ParentID uuid.UUID `json:"parentId"`
	TeenID   uuid.UUID `json:"teenId"`
}

type FamilyUnlinkedEvent struct {
	ParentID uuid.UUID `json:"parentId"`
	TeenID   uuid.UUID `json:"teenId"`
}
