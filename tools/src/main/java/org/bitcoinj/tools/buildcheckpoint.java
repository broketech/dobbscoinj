package org.bitcoinj.tools;

import org.bitcoinj.core.AbstractBlockChainListener;
//import org.bitcoinj.core.NewBestBlockListener;
import org.bitcoinj.core.*;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.params.RegTestParams;
import org.bitcoinj.params.TestNet3Params;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.MemoryBlockStore;
import org.bitcoinj.utils.BriefLogFormatter;
import org.bitcoinj.utils.Threading;
import com.google.common.base.Charsets;
import com.google.common.io.Resources;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.Date;
import java.util.TreeMap;

import static com.google.common.base.Preconditions.checkState;

/**
 * Created by Physicists on 31-03-2016.
 */
public class buildcheckpoint {

    private static final NetworkParameters PARAMS = MainNetParams.get();
    private static final File CHECKPOINTS_FILE = new File("checkpoints");

    public static void main(String[] args) throws Exception {
        BriefLogFormatter.init();

        // Sorted map of UNIX time of block to StoredBlock object.
        final TreeMap<Integer, StoredBlock> checkpoints = new TreeMap<Integer, StoredBlock>();

        // Configure bitcoinj to fetch only headers, not save them to disk, connect to a local fully synced/validated
        // node and to save block headers that are on interval boundaries, as long as they are <1 month old.
        final BlockStore store = new MemoryBlockStore(PARAMS);
        final BlockChain chain = new BlockChain(PARAMS, store);
        final PeerGroup peerGroup = new PeerGroup(PARAMS, chain);
        peerGroup.addAddress(InetAddress.getByName("explorer.dobbscoin.info"));
        long now = new Date().getTime() / 1000;
        peerGroup.setFastCatchupTimeSecs(now);

        final long oneMonthAgo = now - (8640);

        chain.addListener(new AbstractBlockChainListener() {
            @Override
            public void notifyNewBestBlock(StoredBlock block) throws VerificationException {
                int height = block.getHeight();

                if (height % 1000 == 0 && block.getHeader().getTimeSeconds() <= oneMonthAgo) {

                    //               if (height % PARAMS.getInterval() == 0 && block.getHeader().getTimeSeconds() <= oneMonthAgo) {

                    System.out.println(String.format("Checkpointing block %s at height %d",
                            block.getHeader().getHash(), block.getHeight()));
                    checkpoints.put(height, block);
                }
            }
        }, Threading.SAME_THREAD);

        peerGroup.startAsync();
        peerGroup.awaitRunning();
        peerGroup.downloadBlockChain();

        checkState(checkpoints.size() > 0);

        // Write checkpoint data out.
        final FileOutputStream fileOutputStream = new FileOutputStream(CHECKPOINTS_FILE, false);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        final DigestOutputStream digestOutputStream = new DigestOutputStream(fileOutputStream, digest);
        digestOutputStream.on(false);
        final DataOutputStream dataOutputStream = new DataOutputStream(digestOutputStream);
        dataOutputStream.writeBytes("CHECKPOINTS 1");
        dataOutputStream.writeInt(0);  // Number of signatures to read. Do this later.
        digestOutputStream.on(true);
        dataOutputStream.writeInt(checkpoints.size());
        ByteBuffer buffer = ByteBuffer.allocate(StoredBlock.COMPACT_SERIALIZED_SIZE);
        for (StoredBlock block : checkpoints.values()) {
            block.serializeCompact(buffer);
            dataOutputStream.write(buffer.array());
            buffer.position(0);
        }
        dataOutputStream.close();
        Sha256Hash checkpointsHash = new Sha256Hash(digest.digest());
        System.out.println("Hash of checkpoints data is " + checkpointsHash);
        digestOutputStream.close();
        fileOutputStream.close();

        peerGroup.stopAsync();
        peerGroup.awaitTerminated();
        store.close();

        // Sanity check the created file.
        CheckpointManager manager = new CheckpointManager(PARAMS, new FileInputStream(CHECKPOINTS_FILE));
        checkState(manager.numCheckpoints() == checkpoints.size());

        if (PARAMS.getId() == NetworkParameters.ID_MAINNET) {
            //StoredBlock test = manager.getCheckpointBefore(1390500000); // Thu Jan 23 19:00:00 CET 2014
            //checkState(test.getHeight() == 280224);
            //checkState(test.getHeader().getHashAsString()
            //        .equals("5a4e378e1fd0cc77d9e4cfe84216366908e9352b3b5a661c7f0b590e4b077e27"));
        } else if (PARAMS.getId() == NetworkParameters.ID_TESTNET) {
            //StoredBlock test = manager.getCheckpointBefore(1390500000); // Thu Jan 23 19:00:00 CET 2014
            //checkState(test.getHeight() == 167328);
            //checkState(test.getHeader().getHashAsString()
            //        .equals("0000000000035ae7d5025c2538067fe7adb1cf5d5d9c31b024137d9090ed13a9"));
        }

        System.out.println("Checkpoints written to '" + CHECKPOINTS_FILE.getCanonicalPath() + "'.");
    }
}
