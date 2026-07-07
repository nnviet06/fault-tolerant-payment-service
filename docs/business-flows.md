# Business Flows (nghiệp vụ)

What this service does, in domain terms only. For how it is built, see
[technical-design.md](technical-design.md).

## What this service is

An internal **wallet and payment ledger**: it keeps customer balances, moves
money between wallets, reserves money for pending purchases, and keeps a
permanent, tamper-evident record of every movement. Other systems (payout,
notifications, analytics) learn about movements through events it publishes.

There is a single currency, and every amount is a whole number of the smallest
currency unit (like cents). There are no fractional amounts, ever.

## Concepts

| Term | Meaning |
|---|---|
| **Wallet** | An account holding money for one owner. |
| **Balance** | Everything the wallet owns right now. |
| **Held amount** | The part of the balance reserved by active holds. |
| **Available** | Balance minus held amount — what can actually be spent. |
| **Movement** | Money changing place: a deposit, withdrawal, transfer, or hold capture. |
| **Hold** | A temporary reservation of funds with a deadline: "keep 50 aside until this purchase settles." |
| **Ledger** | The permanent, append-only record of all movements. Nothing in it is ever edited or deleted. |
| **Event** | A notification that a movement or hold change happened, for other systems to consume. |

## Flows

### Opening a wallet
A wallet is created with an owner name and starts empty. Reading a wallet
shows its balance, held amount, and available amount.

### Deposit / Withdrawal
A deposit adds money from outside; a withdrawal sends money outside. A
withdrawal is refused when the available amount is too small — being "almost
enough" does not count, and reserved (held) money cannot be withdrawn.

### Transfer
Moves money from one wallet to another in a single all-or-nothing step. There
is no moment where the money has left one wallet and not yet arrived in the
other. A transfer is refused if the sender's available amount is too small.

### Hold → capture, release, or expiry
1. **Create a hold**: reserves an amount on a wallet with a time limit (TTL).
   The money stays in the balance but stops being available.
2. Then exactly **one** of three things happens:
   - **Commit (capture)**: the reserved money moves to a destination wallet.
     This is the actual payment.
   - **Rollback (release)**: the reservation is cancelled; the money becomes
     available again. Nothing moved.
   - **Expiry**: if the deadline passes first, the service releases the
     reservation automatically. Nothing moved.
3. A hold that has already been committed, released, or expired cannot be
   settled a second time — a late commit against an expired hold is refused.

## Guarantees for API consumers

1. **A retried request never charges twice.** Every money operation carries a
   client-chosen `Idempotency-Key`. If the same request is sent again — a
   nervous user, a client retry, a network duplicate, even many copies at the
   same instant — the operation happens once, and every copy receives the
   same answer as the original. Reusing a key for a *different* request is
   rejected as a client error.
2. **No overdrafts, no lost money.** Concurrent operations cannot drive a
   balance negative or make money vanish or appear. Every movement is
   recorded with both its source and destination, so the books always add up.
3. **Events are never lost.** Every movement eventually produces its event,
   even if the service crashes at the worst possible moment. Consumers may
   occasionally receive the same event twice; each event carries a unique id
   so duplicates are recognizable and safe to drop.
4. **History cannot be silently rewritten.** The ledger is append-only, and
   any after-the-fact modification of a recorded movement — even directly in
   the database — is detectable by an integrity check.
5. **Holds cannot leak.** Reserved money is always eventually either captured
   or returned; a forgotten hold is released automatically at its deadline.

## Refusals a client can expect

| Situation | Outcome |
|---|---|
| Spending more than the available amount | Refused: insufficient funds |
| Acting on a hold that was already settled or expired | Refused: hold not active |
| Reusing an `Idempotency-Key` with different request data | Refused: key reuse |
| Missing `Idempotency-Key` on a money operation | Refused: invalid request |
| Amounts that are zero, negative, or not whole numbers | Refused: invalid request |
