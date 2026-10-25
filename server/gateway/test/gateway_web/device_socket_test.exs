defmodule GatewayWeb.DeviceSocketTest do
  use ExUnit.Case, async: true

  import ExUnit.CaptureLog
  import Phoenix.ChannelTest

  @endpoint GatewayWeb.Endpoint

  # Refused connections log at info; keep that out of the test output unless a test fails.
  @moduletag :capture_log

  test "refuses every connection until token verification lands" do
    assert connect(GatewayWeb.DeviceSocket, %{"token" => "unverified"}) == :error
  end

  test "keeps the access token out of the connect log" do
    log = capture_log(fn -> connect(GatewayWeb.DeviceSocket, %{"token" => "secret-access-token"}) end)

    assert log =~ "GatewayWeb.DeviceSocket"
    refute log =~ "secret-access-token"
  end
end
