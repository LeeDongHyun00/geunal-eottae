defmodule GatewayWeb.DeviceSocket do
  @moduledoc """
  The socket devices connect to (gateway.md §4). Until access-token verification lands it refuses every connection,
  so nothing can reach a device topic unauthenticated.
  """
  use Phoenix.Socket

  @impl true
  def connect(_params, _socket, _connect_info), do: :error

  @impl true
  def id(_socket), do: nil
end
