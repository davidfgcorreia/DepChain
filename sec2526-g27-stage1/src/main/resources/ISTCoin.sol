// SPDX-License-Identifier: MIT
pragma solidity ^0.8.26;

// ── IERC20 Interface ──────────────────────────────────────────────────────────
// Standard ERC-20 interface as defined in EIP-20.
interface IERC20 {
    event Transfer(address indexed from, address indexed to, uint256 value);
    event Approval(address indexed owner, address indexed spender, uint256 value);

    function totalSupply()                                          external view returns (uint256);
    function balanceOf(address account)                            external view returns (uint256);
    function transfer(address to, uint256 amount)                  external returns (bool);
    function allowance(address owner, address spender)             external view returns (uint256);
    function approve(address spender, uint256 amount)              external returns (bool);
    function transferFrom(address from, address to, uint256 amount) external returns (bool);
}

// ISTCoin: ERC-20 token used by DepChain.

// ── ERC20 Base (OpenZeppelin-style) ───────────────────────────────────────────
// Implements the full ERC-20 standard following the OpenZeppelin v5 pattern:
//   - Storage: _balances, _allowances, _totalSupply, _name, _symbol
//   - Internal _transfer / _approve / _mint / _burn helpers
//   - Virtual hooks: _beforeTokenTransfer, _afterTokenTransfer
//   - Frontrunning-resistant approve guard enforced in concrete subclass
abstract contract ERC20 is IERC20 {

    string private _name;
    string private _symbol;

    uint256 private _totalSupply;

    mapping(address => uint256) private _balances;
    mapping(address => mapping(address => uint256)) private _allowances;

    constructor(string memory name_, string memory symbol_) {
        _name   = name_;
        _symbol = symbol_;
    }

    // ── Metadata ──────────────────────────────────────────────────────────────

    function name()     public view virtual returns (string memory) { return _name;    }
    function symbol()   public view virtual returns (string memory) { return _symbol;  }
    function decimals() public view virtual returns (uint8)         { return 2;        }

    // ── IERC20 Views ──────────────────────────────────────────────────────────

    function totalSupply() public view virtual override returns (uint256) {
        return _totalSupply;
    }

    function balanceOf(address account) public view virtual override returns (uint256) {
        return _balances[account];
    }

    function allowance(address owner, address spender)
        public view virtual override returns (uint256)
    {
        return _allowances[owner][spender];
    }

    // ── IERC20 Mutators (implemented in subclass for custom guards) ───────────

    function transfer(address to, uint256 amount)
        public virtual override returns (bool)
    {
        _transfer(msg.sender, to, amount);
        return true;
    }

    function approve(address spender, uint256 amount)
        public virtual override returns (bool)
    {
        _approve(msg.sender, spender, amount);
        return true;
    }

    function transferFrom(address from, address to, uint256 amount)
        public virtual override returns (bool)
    {
        _spendAllowance(from, msg.sender, amount);
        _transfer(from, to, amount);
        return true;
    }

    // ── OpenZeppelin Allowance Helpers ────────────────────────────────────────

    /// @notice Atomically increase the allowance granted to `spender`.
    function increaseAllowance(address spender, uint256 addedValue)
        public virtual returns (bool)
    {
        _approve(msg.sender, spender, allowance(msg.sender, spender) + addedValue);
        return true;
    }

    /// @notice Atomically decrease the allowance granted to `spender`.
    ///         Reverts on underflow — this is what defeats the frontrunning attack
    ///         when a spender drains the allowance before the owner can reduce it.
    function decreaseAllowance(address spender, uint256 subtractedValue)
        public virtual returns (bool)
    {
        uint256 current = allowance(msg.sender, spender);
        require(current >= subtractedValue, "ERC20: decreased allowance below zero");
        unchecked {
            _approve(msg.sender, spender, current - subtractedValue);
        }
        return true;
    }

    // ── Internal Core ─────────────────────────────────────────────────────────

    function _transfer(address from, address to, uint256 amount) internal virtual {
        require(from != address(0), "ERC20: transfer from the zero address");
        require(to   != address(0), "ERC20: transfer to the zero address");

        _beforeTokenTransfer(from, to, amount);

        uint256 fromBalance = _balances[from];
        require(fromBalance >= amount, "ERC20: transfer amount exceeds balance");
        unchecked { _balances[from] = fromBalance - amount; }
        _balances[to] += amount;

        emit Transfer(from, to, amount);
        _afterTokenTransfer(from, to, amount);
    }

    function _mint(address account, uint256 amount) internal virtual {
        require(account != address(0), "ERC20: mint to the zero address");

        _beforeTokenTransfer(address(0), account, amount);

        _totalSupply      += amount;
        _balances[account] += amount;

        emit Transfer(address(0), account, amount);
        _afterTokenTransfer(address(0), account, amount);
    }

    function _burn(address account, uint256 amount) internal virtual {
        require(account != address(0), "ERC20: burn from the zero address");

        _beforeTokenTransfer(account, address(0), amount);

        uint256 accountBalance = _balances[account];
        require(accountBalance >= amount, "ERC20: burn amount exceeds balance");
        unchecked { _balances[account] = accountBalance - amount; }
        _totalSupply -= amount;

        emit Transfer(account, address(0), amount);
        _afterTokenTransfer(account, address(0), amount);
    }

    function _approve(address owner, address spender, uint256 amount) internal virtual {
        require(owner   != address(0), "ERC20: approve from the zero address");
        require(spender != address(0), "ERC20: approve to the zero address");

        _allowances[owner][spender] = amount;
        emit Approval(owner, spender, amount);
    }

    function _spendAllowance(address owner, address spender, uint256 amount) internal virtual {
        uint256 current = allowance(owner, spender);
        if (current != type(uint256).max) {
            require(current >= amount, "ERC20: insufficient allowance");
            unchecked { _approve(owner, spender, current - amount); }
        }
    }

    function _beforeTokenTransfer(address from, address to, uint256 amount) internal virtual {}
    function _afterTokenTransfer(address from, address to, uint256 amount)  internal virtual {}
}

// ── ISTCoin ───────────────────────────────────────────────────────────────────
// Concrete ERC-20 token for the DepChain platform.
//
//   Name     : IST Coin
//   Symbol   : IST
//   Decimals : 2
//   Supply   : 100,000,000.00 IST  (= 10_000_000_000 smallest units)
//
// Security properties:
//   - Frontrunning protection: `approve` reverts when current allowance != 0 AND new value != 0.
//     Use `increaseAllowance` / `decreaseAllowance` for safe adjustments.
contract ISTCoin is ERC20 {

    error FrontrunningGuard(address spender, uint256 currentAllowance);

    constructor()
        ERC20("IST Coin", "IST")
    {
        // Mint entire supply to the deployer.
        // 100_000_000 tokens × 10^2 decimals = 10_000_000_000 smallest units.
        _mint(msg.sender, 100_000_000 * (10 ** uint256(decimals())));
    }

    // ── Frontrunning-Resistant approve ────────────────────────────────────────

    /// @notice Safe approve: only allowed when current allowance == 0 OR new amount == 0.
    ///         To change a non-zero allowance use increaseAllowance / decreaseAllowance.
    function approve(address spender, uint256 amount)
        public override returns (bool)
    {
        uint256 current = allowance(msg.sender, spender);
        if (current != 0 && amount != 0) {
            revert FrontrunningGuard(spender, current);
        }
        return super.approve(spender, amount);
    }

    // No token-transfer hook is required; inherit default no-op behavior.
}
