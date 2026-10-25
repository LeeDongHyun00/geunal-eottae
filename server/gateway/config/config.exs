import Config

config :gateway, GatewayWeb.Endpoint,
  adapter: Bandit.PhoenixAdapter,
  pubsub_server: Gateway.PubSub

config :phoenix, :json_library, Jason

# Devices send their access token as a socket parameter, and Phoenix logs connect parameters (gateway.md §7).
config :phoenix, :filter_parameters, ["token"]
