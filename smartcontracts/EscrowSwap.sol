// SPDX-License-Identifier: MIT
// DRAFT
pragma solidity ^0.8.20;

interface IERC20 {
    function approve(address spender, uint256 amount) external returns (bool);
    function transfer(address to, uint256 amount) external returns (bool);
    function balanceOf(address account) external view returns (uint256);
}

contract SimpleEscrow {
    address public seller;
    address public buyer;
    uint256 public amount;
    address public bot;
    bool public isFunded;
    bool public isReleased;

    address public constant MXNB = 0x...; // Replace with MXNB address
    address public constant USDC = 0x...; // Replace with USDC address

    constructor(address _seller, address _buyer, uint256 _amount, address _bot) {
        seller = _seller;
        buyer = _buyer;
        amount = _amount;
        bot = _bot;
    }

    modifier onlyBot() {
        require(msg.sender == bot, "Only bot can call this");
        _;
    }

    function markAsFunded() external onlyBot {
        require(!isFunded, "Already funded");
        require(IERC20(MXNB).balanceOf(address(this)) >= amount, "Insufficient MXNB in escrow");
        isFunded = true;
    }

    function swapToUSDCAndSend(bytes calldata swapCallData, address swapTarget) external onlyBot {
        require(isFunded, "Not funded");
        require(!isReleased, "Already released");

        isReleased = true;

        // Approve 0x to spend MXNB
        require(IERC20(MXNB).approve(swapTarget, amount), "Approval failed");

        // Execute the 0x swap
        (bool success, ) = swapTarget.call(swapCallData);
        require(success, "Swap failed");

        // Transfer resulting USDC to buyer
        uint256 usdcBalance = IERC20(USDC).balanceOf(address(this));
        require(IERC20(USDC).transfer(buyer, usdcBalance), "USDC transfer failed");
    }

    function refundSeller() external onlyBot {
        require(isFunded, "Not funded");
        require(!isReleased, "Already released");

        isReleased = true;
        require(IERC20(MXNB).transfer(seller, amount), "Refund failed");
    }
}

