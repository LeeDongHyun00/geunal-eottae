defmodule GatewayWeb.Endpoint do
  @moduledoc """
  The gateway's whole HTTP surface: the device socket and the health check. Every other request gets 404.
  """
  use Phoenix.Endpoint, otp_app: :gateway

  # Devices connect over WebSocket only and send nothing but heartbeats, hence the small frames (gateway.md §4, §7).
  # `peer_data` feeds the per-address connection limit.
  socket "/socket", GatewayWeb.DeviceSocket,
    websocket: [
      max_frame_size: 16_384,
      connect_info: [:peer_data],
      error_handler: {GatewayWeb.DeviceSocket, :handle_error, []}
    ],
    longpoll: false

  plug GatewayWeb.Health, redis: Gateway.Redis, router: Gateway.RedisRouter
  plug :not_found

  defp not_found(conn, _opts), do: conn |> Plug.Conn.send_resp(404, "") |> Plug.Conn.halt()
end
