-- The agent says where the cash went (2026-11-10, the user: agents collect cash and the company must see what each
-- one is still holding). An agent who has banked the cash, or sent it by mobile money, records the slip or transaction
-- reference against their collections. It is the agent's word, not money received: the receipt stays open until
-- finance records the money and a second person confirms it. What it buys is a reference finance can match against
-- the bank statement, and an agent who no longer reads as sitting on cash they have already paid in.
ALTER TABLE billing.field_receipt
    ADD COLUMN banked_at        TIMESTAMPTZ,
    ADD COLUMN banked_reference VARCHAR(100);

ALTER TABLE billing.field_receipt
    ADD CONSTRAINT field_receipt_banked_together
        CHECK ((banked_at IS NULL) = (banked_reference IS NULL));

-- The agent's own list: what they still hold, newest first.
CREATE INDEX idx_field_receipt_agent ON billing.field_receipt (tenant_id, agent_id, captured_at_server DESC);
