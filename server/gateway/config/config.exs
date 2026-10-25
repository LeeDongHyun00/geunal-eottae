import Config

config :gateway, GatewayWeb.Endpoint,
  adapter: Bandit.PhoenixAdapter,
  pubsub_server: Gateway.PubSub

# Limits from gateway.md §4 and §5: a connection lives at most a day, and a channel whose mailbox grows past this
# many messages is a device that cannot keep up.
config :gateway,
  max_connection_age: :timer.hours(24),
  slow_device_mailbox: 1_000

config :phoenix, :json_library, Jason
config :jose, :json_module, Jason

# Devices send their access token as a socket parameter, and Phoenix logs connect parameters (gateway.md §7).
config :phoenix, :filter_parameters, ["token"]
