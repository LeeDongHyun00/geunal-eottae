defmodule GatewayWeb.DeviceSocket do
  @moduledoc """
  The socket devices connect to (gateway.md §4). `connect/3` verifies the access token and keeps who connected in the
  assigns; a connection that fails gets 403 (or 429 past the per-address limit) and never reaches a channel.
  """
  use Phoenix.Socket

  require Logger

  import Plug.Conn, only: [put_resp_header: 3, send_resp: 3]

  channel "device:*", GatewayWeb.DeviceChannel

  @impl true
  def connect(params, socket, connect_info) do
    with :ok <- check_rate(connect_info),
         {:ok, %{user_id: user_id, device_id: device_id}} <- Gateway.Jwks.verify(params["token"]) do
      # Monotonic, so the 24-hour limit (gateway.md §4) does not move with the wall clock.
      connected_at = System.monotonic_time(:millisecond)
      nonce = Base.url_encode64(:crypto.strong_rand_bytes(9), padding: false)
      {:ok, assign(socket, user_id: user_id, device_id: device_id, connected_at: connected_at, nonce: nonce)}
    else
      {:error, :rate_limited} ->
        {:error, :rate_limited}

      {:error, reason} ->
        # The reason is enough to tell a client bug from an attack; the token itself never gets logged.
        Logger.info("refused a device socket: #{reason}")
        :error
    end
  end

  # A "disconnect" broadcast on this topic closes the connection. The id names the connection, not the device: a slow
  # or day-old connection must not take a fresh one of the same device down with it, and nothing needs to close all of
  # a device's connections at once, since `revoke` reaches each of them through Redis.
  @impl true
  def id(socket), do: "device_socket:#{socket.assigns.device_id}:#{socket.assigns.nonce}"

  @doc "Phoenix's `error_handler` for this socket: 429 with a Retry-After for a rate-limited address."
  def handle_error(conn, :rate_limited) do
    Logger.info("refused a device socket: rate_limited")

    conn
    |> put_resp_header("retry-after", "60")
    |> send_resp(429, "")
  end

  def handle_error(conn, _reason), do: send_resp(conn, 403, "")

  # The transport fills in `peer_data` for real connections; a test socket built without it is not limited.
  defp check_rate(%{peer_data: %{address: address}}) do
    if Gateway.ConnectLimiter.allow?(address), do: :ok, else: {:error, :rate_limited}
  end

  defp check_rate(_connect_info), do: :ok
end
