package identity

import (
	"context"
	"errors"
	"fmt"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"tiyn/internal/identity/domain"
	"tiyn/internal/identity/store"
	"tiyn/internal/platform/db"
	"tiyn/internal/platform/outbox"
)

const maxConflictRetries = 5

var linkAggregateNamespace = uuid.NewSHA1(uuid.NameSpaceOID, []byte("tiyn.identity.family-link"))

type SignInInput struct {
	IDToken     string
	Role        string
	DisplayName string
}

type UserView struct {
	ID          uuid.UUID
	Role        domain.Role
	DisplayName string
	JoinCode    string
}

type SignInResult struct {
	Token string
	User  UserView
}

type JoinResult struct {
	ParentDisplayName string
}

type Service struct {
	store  *db.Store
	tokens *TokenIssuer
	google GoogleVerifier
	codes  domain.JoinCodeSource
	newID  func() (uuid.UUID, error)
}

var (
	_ Authenticator   = (*Service)(nil)
	_ FamilyDirectory = (*Service)(nil)
)

type Option func(*Service)

func WithJoinCodes(codes domain.JoinCodeSource) Option {
	return func(s *Service) { s.codes = codes }
}

func NewService(store *db.Store, tokens *TokenIssuer, google GoogleVerifier, options ...Option) *Service {
	service := &Service{store: store, tokens: tokens, google: google, codes: domain.RandomJoinCodes{}, newID: uuid.NewV7}
	for _, option := range options {
		option(service)
	}
	return service
}

func (s *Service) Verify(_ context.Context, bearer string) (Principal, error) {
	return s.tokens.Verify(bearer)
}

func (s *Service) SignInWithGoogle(ctx context.Context, input SignInInput) (SignInResult, error) {
	identity, err := s.google.Verify(ctx, input.IDToken)
	if err != nil {
		return SignInResult{}, ErrInvalidIdentity
	}
	var user domain.User
	err = retryOnConflict(func() error {
		return s.store.ExecTx(ctx, func(tx pgx.Tx) error {
			var err error
			user, err = s.findOrRegister(ctx, store.New(tx), identity, input)
			return err
		})
	}, store.ErrDuplicateGoogleSub, store.ErrDuplicateJoinCode)
	if err != nil {
		return SignInResult{}, err
	}
	token, err := s.tokens.Issue(user.ID(), user.Role())
	if err != nil {
		return SignInResult{}, err
	}
	return SignInResult{Token: token, User: viewOf(user)}, nil
}

func (s *Service) findOrRegister(ctx context.Context, repo *store.Repository, identity GoogleIdentity, input SignInInput) (domain.User, error) {
	user, err := repo.UserByGoogleSub(ctx, identity.Subject)
	if err == nil {
		return user, nil
	}
	if !errors.Is(err, store.ErrNotFound) {
		return domain.User{}, err
	}
	role, err := domain.ParseRole(input.Role)
	if err != nil {
		return domain.User{}, err
	}
	name := input.DisplayName
	if name == "" {
		name = identity.Name
	}
	id, err := s.newID()
	if err != nil {
		return domain.User{}, fmt.Errorf("generate user id: %w", err)
	}
	user, err = domain.NewUser(id, identity.Subject, role, name, s.codes)
	if err != nil {
		return domain.User{}, err
	}
	if err := repo.InsertUser(ctx, user); err != nil {
		return domain.User{}, err
	}
	return user, nil
}

func (s *Service) Me(ctx context.Context, principal Principal) (UserView, error) {
	user, err := store.New(s.store.Pool()).UserByID(ctx, principal.UserID)
	if errors.Is(err, store.ErrNotFound) {
		return UserView{}, ErrUnauthenticated
	}
	if err != nil {
		return UserView{}, err
	}
	return viewOf(user), nil
}

func (s *Service) JoinWithCode(ctx context.Context, principal Principal, rawCode string) (JoinResult, error) {
	if principal.Role != domain.RoleTeen {
		return JoinResult{}, ErrForbidden
	}
	code, err := domain.ParseJoinCode(rawCode)
	if err != nil {
		return JoinResult{}, err
	}
	var result JoinResult
	err = retryOnConflict(func() error {
		return s.store.ExecTx(ctx, func(tx pgx.Tx) error {
			var err error
			result, err = s.join(ctx, tx, principal.UserID, code)
			return err
		})
	}, store.ErrDuplicateLink)
	return result, err
}

func (s *Service) join(ctx context.Context, tx pgx.Tx, teenID uuid.UUID, code domain.JoinCode) (JoinResult, error) {
	repo := store.New(tx)
	teen, err := repo.UserByID(ctx, teenID)
	if errors.Is(err, store.ErrNotFound) {
		return JoinResult{}, ErrUnauthenticated
	}
	if err != nil {
		return JoinResult{}, err
	}
	parent, err := repo.UserByJoinCode(ctx, code)
	if errors.Is(err, store.ErrNotFound) {
		return JoinResult{}, domain.ErrInvalidJoinCode
	}
	if err != nil {
		return JoinResult{}, err
	}
	link, err := repo.LinkForUpdate(ctx, parent.ID(), teen.ID())
	switch {
	case errors.Is(err, store.ErrNotFound):
		if link, err = domain.NewFamilyLink(parent, teen); err != nil {
			return JoinResult{}, err
		}
		if err := repo.InsertLink(ctx, link); err != nil {
			return JoinResult{}, err
		}
		err = emitLinked(ctx, tx, link)
	case err != nil:
		return JoinResult{}, err
	case link.Activate():
		if err := repo.SaveLink(ctx, link); err != nil {
			return JoinResult{}, err
		}
		err = emitLinked(ctx, tx, link)
	}
	if err != nil {
		return JoinResult{}, err
	}
	return JoinResult{ParentDisplayName: parent.DisplayName()}, nil
}

func (s *Service) RemoveLink(ctx context.Context, principal Principal, teenID uuid.UUID) error {
	if principal.Role != domain.RoleParent {
		return ErrForbidden
	}
	return s.store.ExecTx(ctx, func(tx pgx.Tx) error {
		repo := store.New(tx)
		link, err := repo.LinkForUpdate(ctx, principal.UserID, teenID)
		if errors.Is(err, store.ErrNotFound) {
			return ErrNotFound
		}
		if err != nil {
			return err
		}
		if !link.Remove() {
			return nil
		}
		if err := repo.SaveLink(ctx, link); err != nil {
			return err
		}
		return outbox.Insert(ctx, tx, outbox.Event{
			Type:           FamilyUnlinkedEventType,
			AggregateID:    linkAggregateID(link),
			IdempotencyKey: fmt.Sprintf("FAMILY-UNLINKED-%s-%s-%d", link.ParentID(), link.TeenID(), link.Generation()),
			Payload:        FamilyUnlinkedEvent{ParentID: link.ParentID(), TeenID: link.TeenID()},
		})
	})
}

func (s *Service) AreLinked(ctx context.Context, parentID, teenID uuid.UUID) (bool, error) {
	return store.New(s.store.Pool()).IsLinkActive(ctx, parentID, teenID)
}

func emitLinked(ctx context.Context, tx pgx.Tx, link domain.FamilyLink) error {
	return outbox.Insert(ctx, tx, outbox.Event{
		Type:           FamilyLinkedEventType,
		AggregateID:    linkAggregateID(link),
		IdempotencyKey: fmt.Sprintf("FAMILY-LINKED-%s-%s-%d", link.ParentID(), link.TeenID(), link.Generation()),
		Payload:        FamilyLinkedEvent{ParentID: link.ParentID(), TeenID: link.TeenID()},
	})
}

func linkAggregateID(link domain.FamilyLink) uuid.UUID {
	parent, teen := link.ParentID(), link.TeenID()
	return uuid.NewSHA1(linkAggregateNamespace, append(parent[:], teen[:]...))
}

func viewOf(user domain.User) UserView {
	code, _ := user.JoinCode()
	return UserView{ID: user.ID(), Role: user.Role(), DisplayName: user.DisplayName(), JoinCode: code.String()}
}

func retryOnConflict(attempt func() error, conflicts ...error) error {
	var err error
	for range maxConflictRetries {
		err = attempt()
		if !isOneOf(err, conflicts) {
			return err
		}
	}
	return err
}

func isOneOf(err error, candidates []error) bool {
	for _, candidate := range candidates {
		if errors.Is(err, candidate) {
			return true
		}
	}
	return false
}
