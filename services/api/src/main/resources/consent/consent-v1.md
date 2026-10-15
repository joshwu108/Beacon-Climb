# Beacon Climb consent (version v1)

Beacon Climb is a pseudonymous companion for climbing-board training. This text describes
what the service stores about you and how it is used. The service is for adults. When you
accept, the version `v1` and the SHA-256 of this exact text are recorded with your
account so that it is always clear which text you agreed to.

## What is stored

- A pseudonymous account: the username you choose and a hash of your password. No email
  address, name or other contact details are collected.
- The attempts you log: route, angle, date and time, attempt count, whether you sent the
  route, and the optional effort, completion and notes fields you fill in.
- Your preferences (board, angle, grade band, goals) and the recommendation requests you
  make, together with the recommendations returned to you.
- Session tokens for staying signed in; they expire after 24 hours.

## How it is used: the `service` scope (required)

- To run the service for you: showing your own history, computing features from your own
  attempts and ranking routes for your sessions.
- Your data is processed only for your account. It is not sold, not shared with third
  parties and not used for advertising.

## Research use: the `research` scope (optional, off by default)

- Only if you switch this on may your pseudonymous attempt history be included in model
  training, evaluation datasets or benchmarks. Without it, your data is used solely for
  the service purpose above.

## Your controls

- Revoke consent at any time with `DELETE /v1/me/consent`. Attempts already stored are kept,
  but no new attempts or recommendations are accepted until you consent again.
- Account erasure (`DELETE /v1/me`, planned): your data is deleted after a 30-day grace
  period as described in the retention policy.
- Log out at any time with `DELETE /v1/auth/session`.

Recommendations are suggestions derived from the history you logged. They are not advice
about safety, health or physical ability, and the service makes no assessment of either.
