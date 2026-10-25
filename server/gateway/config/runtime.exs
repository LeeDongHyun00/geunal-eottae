import Config

# Machine-specific settings come from the environment; infra/docker-compose.yml sets them for local runs.
if config_env() == :prod do
  config :gateway,
    redis_url: System.fetch_env!("REDIS_URL"),
    jwks_url: System.fetch_env!("JWKS_URL")

  # TLS ends at the load balancer (gateway.md §7), so the release serves plain HTTP on every interface.
  config :gateway, GatewayWeb.Endpoint,
    server: true,
    http: [ip: {0, 0, 0, 0}, port: String.to_integer(System.get_env("PORT", "4000"))]
else
  config :gateway,
    redis_url: System.get_env("REDIS_URL", "redis://localhost:6379"),
    # The API's port in infra/docker-compose.yml; the tests point this at a JWKS served from the test itself.
    jwks_url: System.get_env("JWKS_URL", "http://localhost:8080/.well-known/jwks.json")

  config :gateway, GatewayWeb.Endpoint, http: [port: String.to_integer(System.get_env("PORT", "4000"))]
end
