package store

import (
	"context"
	"errors"
	"fmt"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgtype"

	"tiyn/internal/identity/domain"
	"tiyn/internal/identity/store/gen"
	"tiyn/internal/platform/db"
)

var (
	ErrNotFound = errors.New("not found")
	ErrConflict = errors.New("concurrent change conflicts with a unique rule")
)

type Repository struct {
	queries *gen.Queries
}

func New(conn gen.DBTX) *Repository {
	return &Repository{queries: gen.New(conn)}
}

func (r *Repository) UserByID(ctx context.Context, id uuid.UUID) (domain.User, error) {
	row, err := r.queries.GetUserByID(ctx, id)
	return userFrom(row, err)
}

func (r *Repository) UserByGoogleSub(ctx context.Context, googleSub string) (domain.User, error) {
	row, err := r.queries.GetUserByGoogleSub(ctx, googleSub)
	return userFrom(row, err)
}

func (r *Repository) UserByJoinCode(ctx context.Context, code domain.JoinCode) (domain.User, error) {
	row, err := r.queries.GetUserByJoinCode(ctx, pgtype.Text{String: code.String(), Valid: true})
	return userFrom(row, err)
}

func (r *Repository) InsertUser(ctx context.Context, user domain.User) error {
	code, hasCode := user.JoinCode()
	err := r.queries.InsertUser(ctx, gen.InsertUserParams{
		ID:          user.ID(),
		GoogleSub:   user.GoogleSub(),
		Role:        string(user.Role()),
		DisplayName: user.DisplayName(),
		JoinCode:    pgtype.Text{String: code.String(), Valid: hasCode},
	})
	return conflictOr(err, "insert user")
}

func (r *Repository) LinkForUpdate(ctx context.Context, parentID, teenID uuid.UUID) (domain.FamilyLink, error) {
	row, err := r.queries.GetLinkForUpdate(ctx, gen.GetLinkForUpdateParams{ParentID: parentID, TeenID: teenID})
	if errors.Is(err, pgx.ErrNoRows) {
		return domain.FamilyLink{}, ErrNotFound
	}
	if err != nil {
		return domain.FamilyLink{}, fmt.Errorf("select family link: %w", err)
	}
	return domain.RehydrateFamilyLink(row.ParentID, row.TeenID, int(row.Generation), row.RemovedAt != nil), nil
}

func (r *Repository) InsertLink(ctx context.Context, link domain.FamilyLink) error {
	err := r.queries.InsertLink(ctx, gen.InsertLinkParams{
		ParentID:   link.ParentID(),
		TeenID:     link.TeenID(),
		Generation: int32(link.Generation()),
	})
	return conflictOr(err, "insert family link")
}

func (r *Repository) SaveLink(ctx context.Context, link domain.FamilyLink) error {
	err := r.queries.UpdateLink(ctx, gen.UpdateLinkParams{
		ParentID:   link.ParentID(),
		TeenID:     link.TeenID(),
		Generation: int32(link.Generation()),
		Removed:    !link.IsActive(),
	})
	if err != nil {
		return fmt.Errorf("update family link: %w", err)
	}
	return nil
}

func (r *Repository) IsLinkActive(ctx context.Context, parentID, teenID uuid.UUID) (bool, error) {
	active, err := r.queries.IsLinkActive(ctx, gen.IsLinkActiveParams{ParentID: parentID, TeenID: teenID})
	if err != nil {
		return false, fmt.Errorf("check family link: %w", err)
	}
	return active, nil
}

func userFrom(row gen.IdentityUser, err error) (domain.User, error) {
	if errors.Is(err, pgx.ErrNoRows) {
		return domain.User{}, ErrNotFound
	}
	if err != nil {
		return domain.User{}, fmt.Errorf("select user: %w", err)
	}
	return domain.RehydrateUser(row.ID, row.GoogleSub, domain.Role(row.Role), row.DisplayName, row.JoinCode.String, row.CreatedAt), nil
}

func conflictOr(err error, action string) error {
	if err == nil {
		return nil
	}
	if db.IsUniqueViolation(err) {
		return ErrConflict
	}
	return fmt.Errorf("%s: %w", action, err)
}
