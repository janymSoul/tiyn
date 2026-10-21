package domain

import "github.com/google/uuid"

type FamilyLink struct {
	parentID   uuid.UUID
	teenID     uuid.UUID
	generation int
	removed    bool
}

func NewFamilyLink(parent, teen User) (FamilyLink, error) {
	if !parent.IsParent() {
		return FamilyLink{}, ErrNotParent
	}
	if !teen.IsTeen() {
		return FamilyLink{}, ErrNotTeen
	}
	return FamilyLink{parentID: parent.ID(), teenID: teen.ID(), generation: 1}, nil
}

func RehydrateFamilyLink(parentID, teenID uuid.UUID, generation int, removed bool) FamilyLink {
	return FamilyLink{parentID: parentID, teenID: teenID, generation: generation, removed: removed}
}

func (l *FamilyLink) Activate() bool {
	if !l.removed {
		return false
	}
	l.removed = false
	l.generation++
	return true
}

func (l *FamilyLink) Remove() bool {
	if l.removed {
		return false
	}
	l.removed = true
	return true
}

func (l FamilyLink) ParentID() uuid.UUID { return l.parentID }
func (l FamilyLink) TeenID() uuid.UUID   { return l.teenID }
func (l FamilyLink) Generation() int     { return l.generation }
func (l FamilyLink) IsActive() bool      { return !l.removed }
