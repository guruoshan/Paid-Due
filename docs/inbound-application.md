# Inbound envelope application

Inbound envelopes are fetched and logged by default, but applying them is OFF in every build. For a deliberate release rollout, pass `-PPAID_DUE_APPLY_INBOUND_FINANCIAL_ENVELOPES=true` to the Gradle command (or set the same environment variable). The default remains `false`.
