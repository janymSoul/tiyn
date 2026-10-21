-- name: GetLinkForUpdate :one
SELECT * FROM identity.family_links
WHERE parent_id = $1 AND teen_id = $2
FOR UPDATE;

-- name: InsertLink :exec
INSERT INTO identity.family_links (parent_id, teen_id, generation)
VALUES ($1, $2, $3);

-- name: UpdateLink :exec
UPDATE identity.family_links
SET generation = $3,
    removed_at = CASE WHEN sqlc.arg(removed)::boolean THEN COALESCE(removed_at, now()) ELSE NULL END
WHERE parent_id = $1 AND teen_id = $2;

-- name: IsLinkActive :one
SELECT EXISTS (
    SELECT 1 FROM identity.family_links
    WHERE parent_id = $1 AND teen_id = $2 AND removed_at IS NULL
);
