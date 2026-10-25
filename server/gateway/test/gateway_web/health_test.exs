defmodule GatewayWeb.HealthTest do
  use ExUnit.Case, async: true

  import Phoenix.ConnTest

  @endpoint GatewayWeb.Endpoint

  test "reports healthy while Redis answers PING" do
    conn = get(build_conn(), "/healthz")

    assert conn.status == 200
    assert conn.resp_body == "ok"
  end

  test "reports unhealthy when Redis does not answer" do
    # Nothing listens on port 1, so this connection never comes up.
    {:ok, redis} = Redix.start_link("redis://127.0.0.1:1", sync_connect: false)

    conn = GatewayWeb.Health.call(build_conn(:get, "/healthz"), GatewayWeb.Health.init(redis: redis))

    assert conn.status == 503
  end

  @tag :capture_log
  test "reports unhealthy when the pub/sub connection does not reach Redis" do
    start_supervised!({Registry, keys: :duplicate, name: :health_test_registry})

    {:ok, router} =
      Gateway.RedisRouter.start_link(
        name: :health_test_router,
        registry: :health_test_registry,
        redis_url: "redis://127.0.0.1:1"
      )

    opts = GatewayWeb.Health.init(redis: Gateway.Redis, router: router)
    conn = GatewayWeb.Health.call(build_conn(:get, "/healthz"), opts)

    assert conn.status == 503
    assert conn.resp_body == "redis pub/sub unavailable"
  end

  test "answers 404 outside the socket and the health check" do
    assert get(build_conn(), "/").status == 404
    assert get(build_conn(), "/api/v1/me").status == 404
  end
end
