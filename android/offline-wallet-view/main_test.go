package main

import (
	"bytes"
	"encoding/binary"
	"encoding/json"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"

	"blocknet/protocol/params"
	"blocknet/wallet"
)

func request(password string) *bytes.Reader {
	var data bytes.Buffer
	_ = binary.Write(&data, binary.BigEndian, uint32(len(password)))
	data.WriteString(password)
	return bytes.NewReader(data.Bytes())
}

func TestExistingWalletOpensLocallyWithoutNode(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("XDG_CONFIG_HOME", dir)
	path := filepath.Join(dir, "existing.wallet.dat")
	created, err := wallet.NewViewOnlyWallet(path, []byte("correct password"), wallet.ViewOnlyKeys{}, wallet.WalletConfig{})
	if err != nil { t.Fatal(err) }
	created.AddOutput(&wallet.OwnedOutput{TxID: [32]byte{1}, Amount: 42, BlockHeight: 0})
	created.RecordSend(&wallet.SendRecord{TxID: [32]byte{2}, Timestamp: 1234, Amount: 7})
	if err := created.Save(); err != nil { t.Fatal(err) }
	before, err := os.ReadFile(path)
	if err != nil { t.Fatal(err) }
	var output bytes.Buffer
	if err := run("mainnet", path, request("correct password"), &output); err != nil { t.Fatal(err) }
	var view savedView
	if err := json.Unmarshal(output.Bytes(), &view); err != nil { t.Fatal(err) }
	if view.Version != 1 || view.SavedAt <= 0 || !view.ViewOnly || view.Address == "" || len(view.History) != 1 || len(view.Sends) != 1 {
		t.Fatalf("unexpected saved view: %+v", view)
	}
	if view.ChainHeightKnown || view.Height != 0 || view.WalletHeight != created.SyncedHeight() { t.Fatal("local reader invented a current chain height") }
	if view.History[0].Amount != 42 || view.Sends[0].TotalAmount != 7 { t.Fatal("saved wallet activity was not projected") }
	if strings.Contains(output.String(), "correct password") || strings.Contains(output.String(), "one_time_priv") || strings.Contains(output.String(), "view_priv") {
		t.Fatal("private wallet material leaked into display JSON")
	}
	after, err := os.ReadFile(path)
	if err != nil { t.Fatal(err) }
	if !bytes.Equal(before, after) { t.Fatal("offline view changed the wallet file") }
	output.Reset()
	if err := run("mainnet", path, request("wrong password"), &output); err == nil || output.Len() != 0 {
		t.Fatal("wrong password opened the wallet or returned display data")
	}
}

func TestPasswordFrameAndPathMustBeValid(t *testing.T) {
	if _, err := readPassword(bytes.NewReader([]byte{0, 0, 0, 0})); err == nil { t.Fatal("empty password accepted") }
	if _, err := readPassword(bytes.NewReader([]byte{0, 0, 16, 1})); err == nil { t.Fatal("oversize password accepted") }
	if _, err := viewWallet("relative.wallet.dat", []byte("pw")); err == nil { t.Fatal("relative wallet path accepted") }
	if err := run("unknown", "/tmp/unused.wallet.dat", request("pw"), &bytes.Buffer{}); err == nil { t.Fatal("unknown network accepted") }
}

func TestTestnetSavedAddressMatchesCore(t *testing.T) {
	if os.Getenv("BLOCKNET_TESTNET_SUBPROCESS") == "" {
		binary, err := os.Executable(); if err != nil { t.Fatal(err) }
		child := exec.Command(binary, "-test.run=^TestTestnetSavedAddressMatchesCore$")
		child.Env = append(os.Environ(), "BLOCKNET_TESTNET_SUBPROCESS=1")
		if output, err := child.CombinedOutput(); err != nil { t.Fatalf("testnet child failed: %v: %s", err, output) }
		return
	}
	params.InitTestnet()
	path := filepath.Join(t.TempDir(), "testnet.wallet.dat")
	created, err := wallet.NewViewOnlyWallet(path, []byte("test password"), wallet.ViewOnlyKeys{}, wallet.WalletConfig{})
	if err != nil { t.Fatal(err) }
	if err := created.Save(); err != nil { t.Fatal(err) }
	expected := created.Address()
	// Simulate the fresh helper's default mainnet globals before run selects testnet.
	params.NetworkID = "blocknet_mainnet"
	params.IsTestnet = false
	var output bytes.Buffer
	if err := run("testnet", path, request("test password"), &output); err != nil { t.Fatal(err) }
	var view savedView
	if err := json.Unmarshal(output.Bytes(), &view); err != nil { t.Fatal(err) }
	if view.Address != expected { t.Fatal("offline testnet address differs from Core") }
}
