defmodule GatewayWeb.Endpoint do
  @moduledoc """
  The gateway's whole HTTP surface: the device socket and the health check. Every other request gets 404.
  """
  use Phoenix.Endpoint, otp_app: :gateway

  # Devices connect over WebSocket only and send nothing but heartbeats, hence the small frames (gateway.md §4, §7).
  socket "/socket", GatewayWeb.DeviceSocket,
    websocket: [max_frame_size: 16_384],
    longpoll: false

  plug GatewayWeb.Health, redis: Gateway.Redis
  plug :not_found

  defp not_found(conn, _opts), do: conn |> Plug.Conn.send_resp(404, "") |> Plug.Conn.halt()
end
