defmodule GatewayWeb.Health do
  @moduledoc """
  `GET /healthz`: 200 while Redis answers PING, 503 otherwise. Without Redis no signal reaches a device, so the load
  balancer and compose should stop sending connections here.
  """
  @behaviour Plug

  import Plug.Conn

  @impl true
  def init(opts), do: Keyword.fetch!(opts, :redis)

  @impl true
  def call(%Plug.Conn{method: "GET", path_info: ["healthz"]} = conn, redis) do
    {status, body} = if redis_up?(redis), do: {200, "ok"}, else: {503, "redis unavailable"}

    conn
    |> put_resp_content_type("text/plain")
    |> send_resp(status, body)
    |> halt()
  end

  def call(conn, _redis), do: conn

  defp redis_up?(redis) do
    Redix.command(redis, ["PING"], timeout: 1_000) == {:ok, "PONG"}
  catch
    # The connection process is restarting.
    :exit, _ -> false
  end
end
