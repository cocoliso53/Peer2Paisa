import dotenv from 'dotenv'
import path from 'path'
dotenv.config({
  path: path.resolve(__dirname, '../../.env'),
})
import { Telegraf, Markup } from "telegraf";
import { createHash, randomUUID } from 'crypto';
import { cancelEscrowFromBot, confirmFiatReceivedFromBot, createEscrowFromBot, markAsFundedFromBot } from '../utils/botSmartContractAdapter';
import { lockMockQuote } from '../pricing/mock';
import { deltaWrapped } from '../utils/cljs-wrapper'
import { getOrderById, updateOrder, getActiveOrderByParticipant, getActiveOrderByParticipantAndState } from '../db';
import type {
    FsmEffects,
    FsmEvent,
    FsmResult,
    OrderDocument,
    OrderState,
    Participant,
} from '../types/fsm'

const explorerBaseURL = "https://basescan.org/"
const helperCreateHash = (): string => {
    const uuid = randomUUID().toString()
    return createHash('md5').update(uuid, 'utf8').digest('base64url')
}

const s_0 = (): OrderDocument => ({
    state: 's0',
    status: 'active',
    orderId: helperCreateHash(),
})

type EffectsResponse = {
    reply?: number[]
    orderMessageId?: number
}

type TelegramMessage = {
    message_id: number
}

type SendMessageExtra = {
    parse_mode?: 'Markdown'
    reply_markup?: {
        inline_keyboard: Array<Array<{ text: string, callback_data: string }>>
    }
}

type TelegramClient = {
    deleteMessage: (chatId: string | number, messageId: number) => Promise<unknown>
    sendMessage: (
        chatId: string | number,
        text: string,
        extra?: SendMessageExtra,
    ) => Promise<TelegramMessage>
}

type BotRuntimeContext = {
    from: {
        id: number
        username?: string
    }
    chat?: {
        id: string | number
    }
    telegram: TelegramClient
    reply: (text: string) => Promise<TelegramMessage>
    deleteMessage: () => Promise<unknown>
    answerCbQuery: () => Promise<unknown>
}

const runEffects = async (ctx: BotRuntimeContext, effects: FsmEffects): Promise<EffectsResponse >=> {
    const res: EffectsResponse = {}

    if (effects.reply) {
        res.reply = []
        for (const i in effects.reply) {
            const m = await ctx.reply(effects.reply[i])
            res.reply.push(m.message_id)
        }
    }

    if (effects.deleteMessage) {
        for (const i in effects.deleteMessage) {
            const { chat, id } = effects.deleteMessage[i]

            try {
                if (chat === "ctx") {
                    await ctx.deleteMessage()
                    continue
                }

                if (chat === "channel" && id) {
                    await ctx.telegram.deleteMessage(channelId, id)
                    continue
                }

                if (chat === "current" && id && ctx.chat?.id !== undefined) {
                    await ctx.telegram.deleteMessage(ctx.chat.id, id)
                }
            } catch (error) {
                console.warn("Unable to delete message", { chat, id, error })
            }
        }
    }

    if (effects.orderBookMessage) {
        for (const orderBookMessage of effects.orderBookMessage) {
            const { text, keyboard } = orderBookMessage
            const { text:inlineText, callback} = keyboard

            const orderMessage = await ctx.telegram.sendMessage(
                channelId,
                text,
                {
                    reply_markup: {
                        inline_keyboard: [
                            [
                                { text: inlineText, callback_data: callback}
                            ]
                        ]
                    }
                }
            )

            res.orderMessageId = orderMessage.message_id
        }
    }

    if (effects.answerQuery) {
        await ctx.answerCbQuery()
    }

    if (effects.sendMessage) {
        await Promise.all(
            effects.sendMessage.map(({ chatId, text, parseMode }) =>
                ctx.telegram.sendMessage(
                    chatId,
                    text,
                    parseMode ? { parse_mode: parseMode } : undefined
                )
            )
        );
    }

    return res
}

const runFsm = (state: OrderDocument, event: FsmEvent): FsmResult =>
    deltaWrapped(state, event)

const persistGeneratedMessageIds = async (
    state: OrderDocument,
    effectResponse: EffectsResponse,
): Promise<OrderDocument> => {
    const { reply, orderMessageId } = effectResponse

    if (!reply?.length && !orderMessageId) {
        return state
    }

    const persistEvent: Extract<FsmEvent, { event: 'setLastMessageId' | 'setMessagesIds' }> = orderMessageId
        ? {
            event: "setMessagesIds",
            data: {
                messageId: reply?.[0],
                orderMessageId,
            },
        }
        : {
            event: "setLastMessageId",
            data: {
                messageId: reply![0],
            },
        }

    return runFsm(state, persistEvent).state
}

const applyFsmEvent = async (
    ctx: BotRuntimeContext,
    state: OrderDocument,
    event: FsmEvent,
    options?: {
        persistGeneratedMessages?: boolean,
    },
) => {
    const { state: nextState, effects } = runFsm(state, event)
    const effectResponse = await runEffects(ctx, effects)
    const stateToPersist = options?.persistGeneratedMessages
        ? await persistGeneratedMessageIds(nextState, effectResponse)
        : nextState

    const persistedState = await updateOrder(stateToPersist)

    return {
        state: persistedState,
        effects,
        effectResponse,
    }
}

const resolveAsyncWorkflowEffects = async (
    ctx: BotRuntimeContext,
    initialState: OrderDocument,
    initialEffects: FsmEffects,
): Promise<OrderDocument> => {
    let currentState = initialState
    let currentEffects = initialEffects

    while (true) {
        if (currentEffects.lockQuote) {
            let quote: ReturnType<typeof lockMockQuote>

            try {
                quote = lockMockQuote(currentEffects.lockQuote)
            } catch (error) {
                const result = await applyFsmEvent(ctx, currentState, {
                    event: 'quoteLockFailed',
                })
                currentState = result.state
                currentEffects = result.effects
                continue
            }

            let escrowAddress: string | undefined

            try {
                const sellerAddress = currentState.seller?.address
                const buyerAddress = currentState.buyer?.address

                if (!sellerAddress || !buyerAddress) {
                    throw new Error('Missing buyer or seller address for escrow creation')
                }

                escrowAddress = await createEscrowFromBot(
                    sellerAddress,
                    buyerAddress,
                    quote.lockedTokenAmount,
                )
            } catch (error) {
                console.error('Unable to create escrow', error)
            }

            const result = escrowAddress
                ? await applyFsmEvent(ctx, currentState, {
                    event: 'escrowCreated',
                    data: {
                        escrow: escrowAddress,
                        escrowUrl: `${explorerBaseURL}/address/${escrowAddress}`,
                        ...quote,
                    },
                })
                : await applyFsmEvent(ctx, currentState, {
                    event: 'escrowCreationFailed',
                })

            currentState = result.state
            currentEffects = result.effects
            continue
        }

        return currentState
    }
}

const channelId = Number(process.env.TELEGRAM_CHANNEL)
const bot = new Telegraf(process.env.TELEGRAM_BOT!);
const orderbook = process.env.ORDERBOOK_LINK

const telegramCommands = [
    { command: 'create', description: 'Create a buy or sell order' },
    { command: 'funded', description: 'Mark escrow as funded' },
    { command: 'release', description: 'Release escrowed USDC' },
    { command: 'cancel', description: 'Cancel your active order' },
    { command: 'help', description: 'Show available commands' },
]

bot.start(async ctx => {
    if (!ctx.from.username) {
        await ctx.reply("Please set a username to use the bot");
        return;
    }

    await ctx.reply(`Welcome. Use this bot to place and configure P2P USDC/MXN orders. Subscribe to ${orderbook} to see all active orders.`);
});

bot.command('create', async ctx => {
    const username = ctx.from.username;

    if (!username) {
        await ctx.reply("Can't use the bot with no username");
        return;
    }

    try {
        const activeResponse = await getActiveOrderByParticipant(username);
        const hasActiveOrder = Boolean(activeResponse?.docs?.length);

        if (hasActiveOrder) {
            await ctx.reply("You can't place a new order while you have one active");
            return;
        }

        await ctx.reply(
            'Sell or buy?',
            Markup.inlineKeyboard([
                [Markup.button.callback('Sell', 'sell'), Markup.button.callback('Buy', 'buy')],
            ]),
        );
    } catch (error) {
        console.error('Unable to check active orders', error);
        await ctx.reply('Something went wrong while checking your orders. Please try again later.');
    }
});

bot.action(/^(sell|buy)$/, async ctx => {
    const action = ctx.match[1] as 'sell' | 'buy';
    const username = ctx.from.username;

    if (!username) {
        await ctx.answerCbQuery();
        await ctx.reply("Please set a username to use the bot");
        return;
    }

    await ctx.answerCbQuery();
    await ctx.deleteMessage();

    await applyFsmEvent(ctx, s_0(), {
        event: action,
        data: { username },
    }, {
        persistGeneratedMessages: true,
    });
});

// NOTE:  Maybe I should use a webhook to replace this logic
bot.command('funded', async ctx => {
    const username = ctx.from.username;

    if (!username) {
        await ctx.reply("Please set a username to use the bot");
        return;
    }

    try {
        const response = await getActiveOrderByParticipantAndState(username, "waitingFunding");
        const activeCouch = response?.docs?.[0];

        if (!activeCouch) {
            await ctx.reply("You have no orders waiting for funding.");
            return;
        }

        if (!activeCouch.escrowAddress) {
            await ctx.reply("Escrow address not available yet. Please wait for the counterpart to share it.");
            return;
        }

        const txHash = await markAsFundedFromBot(activeCouch.escrowAddress);

        if (txHash) {
            await applyFsmEvent(ctx, activeCouch, { event: "escrowFunded" });
        } else {
            await applyFsmEvent(ctx, activeCouch, { event: "escrowFundingFailed" });
        }
    } catch (error) {
        console.error('Error processing /funded command', error);
        await ctx.reply("Something went wrong while checking the escrow. Please try again later.");
    }
});

bot.command('release', async ctx => {
    const username = ctx.from.username;

    if (!username) {
        await ctx.reply("Please set a username to use the bot");
        return;
    }

    try {
        const response = await getActiveOrderByParticipantAndState(username, "waitingRelease");
        const activeCouch = response?.docs?.[0];

        if (!activeCouch) {
            await ctx.reply("You have no orders waiting for release.");
            return;
        }

        if (!activeCouch.escrowAddress) {
            await ctx.reply("Escrow address not available yet. Please try again shortly.");
            return;
        }

        const txHash = await confirmFiatReceivedFromBot(activeCouch.escrowAddress);

        if (txHash) {
            await applyFsmEvent(ctx, activeCouch, {
                event: "fundsReleased",
                data: { explorerUrl: explorerBaseURL + `tx/${txHash}` },
            });
        } else {
            await applyFsmEvent(ctx, activeCouch, { event: "releaseFailed" });
        }
    } catch (error) {
        console.error('Error processing /release command', error);
        await ctx.reply("Something went wrong while releasing the escrow. Please try again later.");
    }
});

bot.command('help', async ctx => {
    await ctx.reply("Use the Telegram command menu to see available actions. For assistance, contact @cuaucortes")
})

bot.command('cancel', async ctx => {
    const username = ctx.from.username;

    if (!username) {
        await ctx.reply("Please set a username to use the bot");
        return;
    }

    try {
        const activeResponse = await getActiveOrderByParticipant(username);
        const activeCouch = activeResponse?.docs?.[0];

        if (!activeCouch) {
            await ctx.reply("You have no active orders.");
            return;
        }

        const { effects } = await applyFsmEvent(ctx, activeCouch, {
            event: "cancel",
            data: { username },
        });

        if (effects.cancelEscrow?.escrowAddress) {
            const cancelResult = await cancelEscrowFromBot(
                effects.cancelEscrow.escrowAddress,
                effects.cancelEscrow.markAsFundedFirst,
            );

            if (cancelResult.status === 'refunded') {
                const explorerUrl = `${explorerBaseURL}tx/${cancelResult.txHash}`;
                const refundMessage = `Funds have been returned on tx ${explorerUrl}`;

                if (activeCouch?.seller?.chatId) {
                    await ctx.telegram.sendMessage(activeCouch.seller.chatId, refundMessage);
                }

                if (activeCouch?.buyer?.chatId) {
                    await ctx.telegram.sendMessage(activeCouch.buyer.chatId, refundMessage);
                }
            } else if (cancelResult.status === 'failed') {
                const supportMessage = "Order canceled, but the escrow refund failed. Please contact support.";

                if (activeCouch?.seller?.chatId) {
                    await ctx.telegram.sendMessage(activeCouch.seller.chatId, supportMessage);
                }

                if (activeCouch?.buyer?.chatId) {
                    await ctx.telegram.sendMessage(activeCouch.buyer.chatId, supportMessage);
                }
            }
        }
    } catch (error) {
        console.error('Error processing /cancel command', error);
        await ctx.reply("Something went wrong while canceling the order. Please try again later.");
    }
});

bot.on('text', async ctx => {
    const username = ctx.from.username;

    if (!username) {
        await ctx.reply("Please set a username to use the bot");
        return;
    }

    const couchResponse = await getActiveOrderByParticipant(username);
    const activeCouch = couchResponse?.docs?.[0];

    if (!activeCouch) {
        await ctx.reply("You have no active orders.");
        return;
    }

    const text = ctx.message.text;
    const baseUser: Participant = {
        username,
        address: text,
        chatId: ctx.from.id,
    };

    const eventObject: Extract<FsmEvent, { event: 'textInput' }> | null = (() => {
        const supportedStates: OrderState[] = [
            'waitingNewOrderAmount',
            'waitingPremium',
            'waitingSetAddress',
            'watingCounterpartAddress',
            'watingCounterpartSetAmount',
        ]

        if (supportedStates.includes(activeCouch.state)) {
            return {
                event: "textInput",
                data: {
                    text,
                    user: baseUser,
                    counterpart: baseUser,
                },
            };
        }

        return null;
    })();

    if (!eventObject) {
        return;
    }

    const { state: stateToPersist, effects } = await applyFsmEvent(ctx, activeCouch, eventObject, {
        persistGeneratedMessages: true,
    });

    await resolveAsyncWorkflowEffects(ctx, stateToPersist, effects)
});

bot.action(/^take:(.+)$/, async ctx => {
    const id = ctx.match[1];
    const orderCouch = await getOrderById(id);

    if (!orderCouch) {
        await ctx.answerCbQuery();
        await ctx.reply("Order not found. It might have been completed or canceled.");
        return;
    }

    const counterpart: Participant = {
        username: ctx.from.username!,
        chatId: ctx.from.id,
    };

    try {
        const amountRange = orderCouch.range
        const event: Extract<FsmEvent, { event: 'takeOrder' | 'takeOrderRange' }> = {
            event: amountRange ? "takeOrderRange" : "takeOrder",
            data: { 
                counterpart,
                takerChatId: ctx.from.id
            }
        }

        await applyFsmEvent(ctx, orderCouch, event);
    } catch (error) {
        console.error(`Error while taking order ${id}`, error);
        await ctx.reply("Something went wrong while taking the order. Please try again later.");
    }
});

bot.on('channel_post', ctx => {
  console.log('Channel ID:', ctx.channelPost.chat.id);
});


bot.telegram.setMyCommands(telegramCommands)
    .catch(error => {
        console.warn('Unable to register Telegram commands', error);
    })

bot.launch()

process.once('SIGINT', () => bot.stop('SIGINT'))
process.once('SIGTERM', () => bot.stop('SIGTERM'))
