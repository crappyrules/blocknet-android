// Blocknet wallet's local, read-only view of an existing encrypted wallet.
// Built against pinned Core wallet source; no daemon, listener, DNS or P2P starts.
package main

import (
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"math"
	"os"
	"path/filepath"
	"sort"
	"strings"

	"blocknet/protocol/params"
	"blocknet/wallet"
)

const maxPassword = 4096
const maxWalletFile = 64 << 20
const historyLimit = 100

type outputView struct {
	TxID        string `json:"txid"`
	OutputIndex int    `json:"output_index"`
	Amount      uint64 `json:"amount"`
	BlockHeight uint64 `json:"block_height"`
	IsCoinbase  bool   `json:"is_coinbase"`
	Spent       bool   `json:"spent"`
	SpentHeight uint64 `json:"spent_height,omitempty"`
}

type sendView struct {
	TxID           string `json:"txid"`
	Timestamp      int64  `json:"timestamp"`
	RecordedHeight uint64 `json:"recorded_height"`
	TotalAmount    uint64 `json:"total_amount"`
	Fee            uint64 `json:"fee"`
}

type savedView struct {
	Version      int          `json:"version"`
	SavedAt      int64        `json:"savedAt"`
	Spendable    uint64       `json:"spendable"`
	Pending      uint64       `json:"pending"`
	Height       uint64       `json:"height"`
	WalletHeight uint64       `json:"walletHeight"`
	ChainHeightKnown bool     `json:"chainHeightKnown"`
	ViewOnly     bool         `json:"viewOnly"`
	Address      string       `json:"address"`
	History      []outputView `json:"history"`
	Sends        []sendView   `json:"sends"`
}

func readPassword(input io.Reader) ([]byte, error) {
	var length [4]byte
	if _, err := io.ReadFull(input, length[:]); err != nil { return nil, err }
	n := binary.BigEndian.Uint32(length[:])
	if n == 0 || n > maxPassword { return nil, errors.New("invalid password length") }
	password := make([]byte, n)
	if _, err := io.ReadFull(input, password); err != nil {
		wipe(password)
		return nil, err
	}
	return password, nil
}

func wipe(bytes []byte) { for i := range bytes { bytes[i] = 0 } }

func viewWallet(path string, password []byte) (savedView, error) {
	if !filepath.IsAbs(path) || !strings.HasSuffix(path, ".wallet.dat") {
		return savedView{}, errors.New("invalid wallet path")
	}
	info, err := os.Stat(path)
	if err != nil { return savedView{}, err }
	if !info.Mode().IsRegular() || info.Size() < 1 || info.Size() > maxWalletFile {
		return savedView{}, errors.New("invalid wallet file")
	}
	loaded, err := wallet.LoadWallet(path, password, wallet.WalletConfig{})
	if err != nil { return savedView{}, errors.New("wallet password or file is invalid") }
	height := loaded.SyncedHeight()
	spendable := loaded.SpendableBalance(height)
	pending := loaded.PendingBalance(height)
	if pending > math.MaxInt64-loaded.PendingUnconfirmedBalance() ||
		height > math.MaxInt64 || spendable > math.MaxInt64 {
		return savedView{}, errors.New("wallet values are unsupported")
	}
	pending += loaded.PendingUnconfirmedBalance()
	view := savedView{
		Version: 1, SavedAt: info.ModTime().UnixMilli(), Spendable: spendable,
		Pending: pending, Height: 0, WalletHeight: height, ChainHeightKnown: false,
		ViewOnly: loaded.IsViewOnly(), Address: loaded.Address(),
		History: []outputView{}, Sends: []sendView{},
	}
	if view.SavedAt <= 0 || view.Address == "" { return savedView{}, errors.New("invalid wallet state") }
	outputs := loaded.AllOutputs()
	sort.Slice(outputs, func(i, j int) bool {
		if outputs[i].BlockHeight != outputs[j].BlockHeight { return outputs[i].BlockHeight > outputs[j].BlockHeight }
		if outputs[i].TxID != outputs[j].TxID { return hex.EncodeToString(outputs[i].TxID[:]) < hex.EncodeToString(outputs[j].TxID[:]) }
		return outputs[i].OutputIndex < outputs[j].OutputIndex
	})
	for _, out := range outputs {
		if len(view.History) >= historyLimit { break }
		if out.Amount > math.MaxInt64 || out.BlockHeight > math.MaxInt64 || out.SpentHeight > math.MaxInt64 { return savedView{}, errors.New("wallet values are unsupported") }
		view.History = append(view.History, outputView{hex.EncodeToString(out.TxID[:]), out.OutputIndex, out.Amount, out.BlockHeight, out.IsCoinbase, out.Spent, out.SpentHeight})
	}
	sends := loaded.SendRecords()
	usable := sends[:0]
	for _, record := range sends { if record != nil { usable = append(usable, record) } }
	sends = usable
	sort.Slice(sends, func(i, j int) bool {
		if sends[i].Timestamp != sends[j].Timestamp { return sends[i].Timestamp > sends[j].Timestamp }
		return hex.EncodeToString(sends[i].TxID[:]) > hex.EncodeToString(sends[j].TxID[:])
	})
	for _, record := range sends {
		if len(view.Sends) >= historyLimit { break }
		if record.BlockHeight > math.MaxInt64 || record.TotalAmount() > math.MaxInt64 || record.Fee > math.MaxInt64 { return savedView{}, errors.New("wallet values are unsupported") }
		view.Sends = append(view.Sends, sendView{hex.EncodeToString(record.TxID[:]), record.Timestamp, record.BlockHeight, record.TotalAmount(), record.Fee})
	}
	return view, nil
}

func run(network, path string, input io.Reader, output io.Writer) error {
	if network != "mainnet" && network != "testnet" { return errors.New("invalid wallet network") }
	if network == "testnet" { params.InitTestnet() }
	password, err := readPassword(input)
	if err != nil { return err }
	defer wipe(password)
	view, err := viewWallet(path, password)
	if err != nil { return err }
	return json.NewEncoder(output).Encode(view)
}

func main() {
	if len(os.Args) != 3 || run(os.Args[1], os.Args[2], os.Stdin, os.Stdout) != nil {
		os.Exit(1)
	}
}
