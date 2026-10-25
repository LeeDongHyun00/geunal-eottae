defmodule GatewayWeb.Health do
  @moduledoc """
  `GET /healthz`: 200 while both Redis connections answer, 503 otherwise. Without the command connection the node
  cannot tell, and without the pub/sub connection no signal reaches a device, so either way the load balancer and
  compose should stop sending connections here.
  """
  @behaviour Plug

  import Plug.Conn

  @impl true
  def init(opts), do: {Keyword.fetch!(opts, :redis), Keyword.get(opts, :router)}

  @impl true
  def call(%Plug.Conn{method: "GET", path_info: ["healthz"]} = conn, {redis, router}) do
    {status, body} =
      cond do
        not redis_up?(redis) -> {503, "redis unavailable"}
        router != nil and not Gateway.RedisRouter.connected?(router) -> {503, "redis pub/sub unavailable"}
        true -> {200, "ok"}
      end

    conn
    |> put_resp_content_type("text/plain")
    |> send_resp(status, body)
    |> halt()
  end

  def call(conn, _opts), do: conn

  defp redis_up?(redis) do
    Redix.command(redis, ["PING"], timeout: 1_000) == {:ok, "PONG"}
  catch
    # The connection process is restarting.
    :exit, _ -> false
  end
end
