defmodule GatewayWeb.DeviceSocketTest do
  use ExUnit.Case, async: true

  import ExUnit.CaptureLog
  import Phoenix.ChannelTest

  alias GatewayTest.Keys
  alias GatewayWeb.DeviceSocket

  @endpoint GatewayWeb.Endpoint

  # Refused connections log at info; keep that out of the test output unless a test fails.
  @moduletag :capture_log

  test "accepts a token the API signed and remembers whose connection it is" do
    user_id = Keys.uuid()
    device_id = Keys.uuid()

    assert {:ok, socket} =
             connect(DeviceSocket, %{"token" => Keys.token(Keys.main(), %{"sub" => user_id, "did" => device_id})})

    assert %{user_id: ^user_id, device_id: ^device_id, connected_at: connected_at} = socket.assigns
    assert is_integer(connected_at)
    assert String.starts_with?(socket.id, "device_socket:#{device_id}:")

    # Each connection has an id of its own, so closing one leaves the device's other connections alone.
    {:ok, again} = connect(DeviceSocket, %{"token" => Keys.token(Keys.main(), %{"sub" => user_id, "did" => device_id})})
    assert again.id != socket.id
  end

  test "refuses a token it cannot verify, with the reason in the log but not the token" do
    stranger = Keys.generate()
    token = Keys.token(stranger)

    log = capture_log(fn -> assert connect(DeviceSocket, %{"token" => token}) == :error end)

    assert log =~ "refused a device socket: unknown_kid"
    refute log =~ token
  end

  test "refuses an expired token and a missing one" do
    assert connect(DeviceSocket, %{"token" => Keys.token(Keys.main(), %{"exp" => 0})}) == :error
    assert connect(DeviceSocket, %{}) == :error
    assert connect(DeviceSocket, %{"token" => "unverified"}) == :error
  end

  test "keeps the access token out of Phoenix's connect log" do
    token = Keys.token(Keys.main())

    log = capture_log(fn -> connect(DeviceSocket, %{"token" => token}) end)

    assert log =~ "GatewayWeb.DeviceSocket"
    refute log =~ token
  end

  test "limits connection attempts per address, counting the refused ones too" do
    address = {10, 99, :rand.uniform(255), :rand.uniform(255)}
    connect_info = %{peer_data: %{address: address, port: 50_000, ssl_cert: nil}}

    for _ <- 1..15,
        do: assert({:ok, _} = connect(DeviceSocket, %{"token" => Keys.token(Keys.main())}, connect_info: connect_info))

    for _ <- 1..15, do: assert(connect(DeviceSocket, %{"token" => "bad"}, connect_info: connect_info) == :error)

    assert connect(DeviceSocket, %{"token" => Keys.token(Keys.main())}, connect_info: connect_info) ==
             {:error, :rate_limited}

    # Another address is not affected.
    other = %{peer_data: %{address: {10, 98, 0, 1}, port: 50_000, ssl_cert: nil}}
    assert {:ok, _} = connect(DeviceSocket, %{"token" => Keys.token(Keys.main())}, connect_info: other)
  end

  test "answers a limited address with 429 and a Retry-After" do
    conn = DeviceSocket.handle_error(Phoenix.ConnTest.build_conn(:get, "/socket/websocket"), :rate_limited)

    assert conn.status == 429
    assert Plug.Conn.get_resp_header(conn, "retry-after") == ["60"]
  end
end
