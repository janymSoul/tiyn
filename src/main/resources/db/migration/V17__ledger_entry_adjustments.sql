ALTER TABLE ledger.ledger_entries
    DROP CONSTRAINT ledger_entries_kind_check,
    DROP CONSTRAINT ledger_entries_check,
    DROP CONSTRAINT ledger_entries_check1;

ALTER TABLE ledger.ledger_entries
    ADD CONSTRAINT ledger_entries_kind_check
        CHECK (kind IN ('TASK_REWARD', 'PAYOUT', 'PENALTY', 'GOAL_LOCK', 'GOAL_UNLOCK', 'MANUAL_BONUS', 'MANUAL_PENALTY')),
    ADD CONSTRAINT ledger_entries_ref_check
        CHECK (kind IN ('PENALTY', 'MANUAL_BONUS', 'MANUAL_PENALTY') OR ref_id IS NOT NULL),
    ADD CONSTRAINT ledger_entries_delta_check
        CHECK (
            CASE kind
                WHEN 'TASK_REWARD'    THEN balance_delta > 0 AND locked_delta = 0
                WHEN 'PAYOUT'         THEN balance_delta < 0 AND locked_delta = 0
                WHEN 'PENALTY'        THEN balance_delta < 0 AND locked_delta = 0
                WHEN 'GOAL_LOCK'      THEN balance_delta = 0 AND locked_delta > 0
                WHEN 'GOAL_UNLOCK'    THEN balance_delta = 0 AND locked_delta < 0
                WHEN 'MANUAL_BONUS'   THEN balance_delta > 0 AND locked_delta = 0
                WHEN 'MANUAL_PENALTY' THEN balance_delta < 0 AND locked_delta = 0
            END
        ),
    ADD CONSTRAINT ledger_entries_manual_actor_check
        CHECK (
            kind NOT IN ('MANUAL_BONUS', 'MANUAL_PENALTY')
            OR (actor_id IS NOT NULL AND note IS NOT NULL AND length(note) BETWEEN 1 AND 140)
        );
