import Config

config :gateway, GatewayWeb.Endpoint,
  adapter: Bandit.PhoenixAdapter,
  pubsub_server: Gateway.PubSub

# Limits from gateway.md §4, §5 and §7: a connection lives at most a day, a channel whose mailbox grows past this many
# messages is a device that cannot keep up, and a device that sends more frames than this in a minute is not one of
# ours (a join and two heartbeats a minute is what they send).
config :gateway,
  max_connection_age: :timer.hours(24),
  slow_device_mailbox: 1_000,
  max_frames_per_minute: 30

# Proxies whose X-Forwarded-For names the client (Gateway.ClientAddress); runtime.exs reads TRUSTED_PROXIES in prod.
# The tests trust TEST-NET-1, which no other test address comes from.
config :gateway, trusted_proxies: if(config_env() == :test, do: "192.0.2.0/24", else: "")

# Debug logs carry what devices send in events (gateway.md §7: no payloads in the logs).
if config_env() == :prod, do: config(:logger, level: :info)

config :phoenix, :json_library, Jason
config :jose, :json_module, Jason

# Devices send their access token as a socket parameter, and Phoenix logs connect parameters (gateway.md §7).
config :phoenix, :filter_parameters, ["token"]
