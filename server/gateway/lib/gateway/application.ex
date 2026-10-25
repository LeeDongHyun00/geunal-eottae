defmodule Gateway.Application do
  @moduledoc false
  use Application

  @impl true
  def start(_type, _args) do
    children = [
      {Phoenix.PubSub, name: Gateway.PubSub},
      # Connects in the background and keeps reconnecting, so the gateway starts and reports unhealthy while Redis is
      # away instead of crashing.
      {Redix, {Application.fetch_env!(:gateway, :redis_url), [name: Gateway.Redis]}},
      GatewayWeb.Endpoint
    ]

    Supervisor.start_link(children, strategy: :one_for_one, name: Gateway.Supervisor)
  end
end
