// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

contract SimpleEscrow {
    address public seller;
    address public buyer;
    uint256 public amount;
    bool public isFunded;
    bool public isReleased;

    address public bot; // address/wallet/smarcontract that will release the funds if no dispute

    address public constant token = 0x82B9e52b26A2954E113F94Ff26647754d5a4247D; // MXNB on testnet

    constructor(address _seller, address _buyer, uint256 _amount, address _bot) {
        seller = _seller;
        buyer = _buyer;
        amount = _amount;
        bot = _bot;
    }

    function markAsFunded() external {
        require(msg.sender == bot, "Only trusted bot can mark as funded");
        require(!isFunded, "Already funded");
        require(IERC20(token).balanceOf(address(this)) >= amount, "Insufficient token balance in escrow");

        isFunded = true;
    }

    function confirmFiatReceived() external {
        require(msg.sender == bot, "Only trusted bot can confirm");
        require(isFunded, "Escrow not funded yet");
        require(!isReleased, "Funds already released");

        isReleased = true;
        require(IERC20(token).transfer(buyer, amount), "Token release failed");
    }

    function refundSeller() external {
        require(msg.sender == bot, "Only bot can refund");
        require(isFunded, "Escrow not funded");
        require(!isReleased, "Already released");

        isReleased = true;
        require(IERC20(token).transfer(seller, amount), "Refund failed");
    }
}

interface IERC20 {
    function transfer(address to, uint256 amount) external returns (bool);
    function transferFrom(address from, address to, uint256 amount) external returns (bool);
    function balanceOf(address account) external view returns (uint256);
}

