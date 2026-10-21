-- name: GetUserByID :one
SELECT * FROM identity.users WHERE id = $1;

-- name: GetUserByGoogleSub :one
SELECT * FROM identity.users WHERE google_sub = $1;

-- name: GetUserByJoinCode :one
SELECT * FROM identity.users WHERE join_code = $1;

-- name: InsertUser :exec
INSERT INTO identity.users (id, google_sub, role, display_name, join_code)
VALUES ($1, $2, $3, $4, $5);
