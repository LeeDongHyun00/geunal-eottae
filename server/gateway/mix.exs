defmodule Gateway.MixProject do
  use Mix.Project

  def project do
    [
      app: :gateway,
      version: "0.1.0",
      elixir: "~> 1.20",
      start_permanent: Mix.env() == :prod,
      deps: deps(),
      releases: [gateway: [include_executables_for: [:unix]]]
    ]
  end

  def application do
    [mod: {Gateway.Application, []}, extra_applications: [:logger]]
  end

  defp deps do
    [
      {:bandit, "~> 1.13"},
      {:credo, "~> 1.7", only: [:dev, :test], runtime: false},
      {:jason, "~> 1.4"},
      {:phoenix, "~> 1.8.16"},
      {:redix, "~> 1.10"}
    ]
  end
end
