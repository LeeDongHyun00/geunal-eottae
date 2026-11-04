defmodule Gateway.Application do
  @moduledoc false
  use Application

  @impl true
  def start(_type, _args) do
    # Parsed here rather than on each connection, so a malformed TRUSTED_PROXIES stops the boot.
    Gateway.ClientAddress.put_trusted(
      Gateway.ClientAddress.parse_cidrs!(Application.get_env(:gateway, :trusted_proxies))
    )

    children = [
      {Phoenix.PubSub, name: Gateway.PubSub},
      # Connects in the background and keeps reconnecting, so the gateway starts and reports unhealthy while Redis is
      # away instead of crashing.
      {Redix, {Application.fetch_env!(:gateway, :redis_url), [name: Gateway.Redis]}},
      {Registry, keys: :duplicate, name: Gateway.DeviceRegistry},
      Gateway.RedisRouter,
      Gateway.Jwks,
      Gateway.ConnectLimiter,
      GatewayWeb.Endpoint
    ]

    Supervisor.start_link(children, strategy: :one_for_one, name: Gateway.Supervisor)
  end
end
