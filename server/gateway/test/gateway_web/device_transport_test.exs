defmodule GatewayWeb.DeviceTransportTest do
  use ExUnit.Case, async: true

  alias GatewayTest.Keys
  alias GatewayWeb.DeviceTransport

  @moduletag :capture_log

  # The transport as the WebSocket adapter drives it, without a network connection in between.
  defp open do
    config = %{
      endpoint: GatewayWeb.Endpoint,
      transport: :websocket,
      options: [serializer: [{Phoenix.Socket.V2.JSONSerializer, "~> 2.0.0"}]],
      params: %{"token" => Keys.token(Keys.main()), "vsn" => "2.0.0"},
      connect_info: %{}
    }

    {:ok, state} = DeviceTransport.connect(config)
    {:ok, state} = DeviceTransport.init(state)
    state
  end

  defp heartbeat(ref), do: {Jason.encode!([nil, "#{ref}", "phoenix", "heartbeat", %{}]), opcode: :text}

  defp wait_out_window_end do
    left = :timer.minutes(1) - rem(System.monotonic_time(:millisecond), :timer.minutes(1))
    if left < :timer.seconds(2), do: Process.sleep(left)
  end

  test "answers heartbeats up to the per-minute cap and closes the connection past it" do
    wait_out_window_end()
    limit = Application.fetch_env!(:gateway, :max_frames_per_minute)

    state =
      Enum.reduce(1..limit, open(), fn ref, state ->
        assert {:reply, :ok, {:text, reply}, state} = DeviceTransport.handle_in(heartbeat(ref), state)
        assert [nil, _ref, "phoenix", "phx_reply", %{"status" => "ok"}] = Jason.decode!(reply)
        state
      end)

    assert {:stop, {:shutdown, :too_many_frames}, _state} = DeviceTransport.handle_in(heartbeat(0), state)
  end

  test "still closes on a disconnect broadcast" do
    state = open()
    broadcast = %Phoenix.Socket.Broadcast{topic: "t", event: "disconnect", payload: %{}}
    assert {:stop, {:shutdown, :disconnected}, _state} = DeviceTransport.handle_info(broadcast, state)
  end
end
